package com.deviante

import com.deviante.dto.CreateActivityRequest
import com.deviante.dto.AnalysisDriftResponse
import com.deviante.dto.DeleteProcessRequest
import com.deviante.dto.ErrorResponse
import com.deviante.dto.EventLogUploadResponse
import com.deviante.dto.MapOperationRequest
import com.deviante.dto.ResolveMappingRequest
import com.deviante.dto.ResolveMappingResponse
import com.deviante.dto.RenameProcessRequest
import com.deviante.dto.ProcessAnalysisResponse
import com.deviante.dto.UnmappedOperationResponse
import com.deviante.dto.UpdateActivityRequest
import com.deviante.dto.UpdateManagerRequest
import com.deviante.dto.UpdateProcessRequest
import com.deviante.dto.validateProcessDeletion
import com.deviante.dto.toResponse
import com.deviante.model.ProcessRecord
import com.deviante.repository.ActivitiesRepository
import com.deviante.repository.AnalysisRepository
import com.deviante.repository.EventLogIngestionRepository
import com.deviante.repository.EventLogsRepository
import com.deviante.repository.ManagerRepository
import com.deviante.repository.MappingRepository
import com.deviante.repository.OperationsRepository
import com.deviante.repository.ProcessGraphRepository
import com.deviante.repository.ProcessRepository
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.io.readByteArray
import java.util.UUID

/**
 * Authenticates, resolves the Manager, and checks the process is visible to
 * them — the same four-step preamble every process-scoped route needs.
 * Responds with the right status itself and returns null when it fails.
 */
private suspend fun ApplicationCall.requireProcess(
    authClient: SupabaseAuthClient,
    managerRepository: ManagerRepository,
    processRepository: ProcessRepository,
    processId: UUID,
): ProcessRecord? {
    val supabaseUser = requireSupabaseUser(authClient) ?: return null
    managerRepository.findOrCreateForSupabaseUser(
        supabaseUser.id,
        supabaseUser.email,
        supabaseUser.fullNameHint,
    )
    val process = processRepository.findById(processId)
    if (process == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("Processo não encontrado."))
        return null
    }
    return process
}

/** Reads the first file part of a multipart upload, discarding the rest. */
private suspend fun ApplicationCall.receiveUploadedFile(): Pair<String, ByteArray>? {
    var result: Pair<String, ByteArray>? = null

    receiveMultipart().forEachPart { part ->
        if (result == null && part is PartData.FileItem) {
            val name = part.originalFileName.orEmpty()
            if (name.isNotBlank()) {
                result = name to part.provider().readRemaining().readByteArray()
            }
        }
        part.dispose()
    }

    return result
}

private suspend fun ApplicationCall.requireSupabaseUser(authClient: SupabaseAuthClient): SupabaseUser? {
    val header = request.headers[HttpHeaders.Authorization]
    val token = header?.removePrefix("Bearer ")?.trim()
    if (token.isNullOrBlank()) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Você precisa estar autenticado."))
        return null
    }

    val user = authClient.verify(token)
    if (user == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse("Sessão inválida ou expirada."))
        return null
    }
    return user
}

fun Application.configureRouting() {
    val authClient = configureSupabaseAuthClient()
    val managerRepository = ManagerRepository()
    val processRepository = ProcessRepository()
    val activitiesRepository = ActivitiesRepository()
    val operationsRepository = OperationsRepository()
    val eventLogsRepository = EventLogsRepository()
    val ingestionRepository = EventLogIngestionRepository()
    val mappingRepository = MappingRepository()
    val graphRepository = ProcessGraphRepository()
    val analysisRepository = AnalysisRepository()
    val miningClient = configureMiningClient()

    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
        get("/ping") {
            call.respondText("pong")
        }

        route("/api") {
            route("/manager") {
                get("/me") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@get
                    val manager = managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    call.respond(manager.toResponse())
                }

                put("/me") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@put
                    val body = call.receive<UpdateManagerRequest>()

                    val fieldErrors = mutableMapOf<String, String>()
                    if (body.fullName.isBlank()) fieldErrors["fullName"] = "Nome completo é obrigatório."
                    if (fieldErrors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Corrija os campos destacados.", fieldErrors))
                        return@put
                    }

                    // Ensure the manager row exists (first update right after OAuth login).
                    managerRepository.findOrCreateForSupabaseUser(supabaseUser.id, supabaseUser.email, supabaseUser.fullNameHint)

                    val updated = managerRepository.update(
                        userId = supabaseUser.id,
                        fullName = body.fullName.trim(),
                    )
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Conta não encontrada."))
                        return@put
                    }
                    call.respond(updated.toResponse())
                }
            }

            route("/processes") {
                get {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@get
                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val processes = processRepository.listAll().map { it.toResponse() }
                    call.respond(processes)
                }

                post {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@post
                    val manager = managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val process = processRepository.create(manager.id)
                    call.respond(HttpStatusCode.Created, process.toResponse())
                }

                get("/{id}") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@get
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@get
                    }
                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val process = processRepository.findById(id)
                    if (process == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Processo não encontrado."))
                        return@get
                    }
                    call.respond(process.toResponse())
                }

                put("/{id}") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@put
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@put
                    }
                    val body = call.receive<UpdateProcessRequest>()

                    val fieldErrors = mutableMapOf<String, String>()
                    if (body.name.isBlank()) {
                        fieldErrors["name"] = "O nome do processo não pode ficar em branco."
                    } else if (body.name.trim().length > 100) {
                        fieldErrors["name"] = "O nome do processo deve ter no máximo 100 caracteres."
                    }
                    if (body.companyName.isBlank()) {
                        fieldErrors["companyName"] = "O nome da empresa é obrigatório."
                    } else if (body.companyName.trim().length > 255) {
                        fieldErrors["companyName"] = "O nome da empresa deve ter no máximo 255 caracteres."
                    }
                    if (fieldErrors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Corrija os campos destacados.", fieldErrors))
                        return@put
                    }

                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val updated = processRepository.update(
                        id = id,
                        name = body.name.trim(),
                        companyName = body.companyName.trim(),
                        description = body.description.trim(),
                        sector = body.sector.trim(),
                    )
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Processo não encontrado."))
                        return@put
                    }
                    call.respond(updated.toResponse())
                }

                patch("/{id}/name") {
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@patch
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, id) == null) {
                        return@patch
                    }

                    val name = call.receive<RenameProcessRequest>().name.trim()
                    val nameError = when {
                        name.isBlank() -> "O nome do processo não pode ficar em branco."
                        name.length > 100 -> "O nome do processo deve ter no máximo 100 caracteres."
                        else -> null
                    }
                    if (nameError != null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Corrija o nome do processo.", mapOf("name" to nameError)),
                        )
                        return@patch
                    }

                    val updated = processRepository.updateName(id, name)
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Processo não encontrado."))
                        return@patch
                    }
                    call.respond(updated.toResponse())
                }

                delete("/{id}") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@delete
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@delete
                    }
                    val manager = managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    if (!manager.isOwner()) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("Somente o proprietário pode excluir processos."),
                        )
                        return@delete
                    }

                    val process = processRepository.findById(id)
                    if (process == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Processo não encontrado."))
                        return@delete
                    }

                    val body = call.receive<DeleteProcessRequest>()
                    val fieldErrors = validateProcessDeletion(body, process.name)
                    if (fieldErrors.isNotEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Confirme a exclusão do processo.", fieldErrors),
                        )
                        return@delete
                    }

                    val deleted = processRepository.delete(id)
                    if (!deleted) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Processo não encontrado."))
                        return@delete
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/activities") {
                get {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@get
                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val activities = activitiesRepository.listAll().map { it.toResponse() }
                    call.respond(activities)
                }

                post {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@post
                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val body = call.receive<CreateActivityRequest>()

                    val fieldErrors = mutableMapOf<String, String>()
                    if (body.name.isBlank()) fieldErrors["name"] = "Nome da atividade é obrigatório."
                    if (fieldErrors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Corrija os campos destacados.", fieldErrors))
                        return@post
                    }

                    val activity = activitiesRepository.create(
                        name = body.name.trim(),
                        description = body.description.trim(),
                    )
                    call.respond(HttpStatusCode.Created, activity.toResponse())
                }

                get("/{id}") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@get
                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de atividade inválido."))
                        return@get
                    }
                    val activity = activitiesRepository.findById(id)
                    if (activity == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Atividade não encontrada."))
                        return@get
                    }
                    call.respond(activity.toResponse())
                }

                put("/{id}") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@put
                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de atividade inválido."))
                        return@put
                    }
                    val body = call.receive<UpdateActivityRequest>()

                    val fieldErrors = mutableMapOf<String, String>()
                    if (body.name.isBlank()) fieldErrors["name"] = "Nome da atividade é obrigatório."
                    if (fieldErrors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Corrija os campos destacados.", fieldErrors))
                        return@put
                    }

                    val updated = activitiesRepository.update(
                        id = id,
                        name = body.name.trim(),
                        description = body.description.trim(),
                    )
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Atividade não encontrada."))
                        return@put
                    }
                    call.respond(updated.toResponse())
                }
            }

            /**
             * UC3 — the manually defined activity set for one process.
             *
             * This model is intentionally separate from `/graph`: membership
             * expresses what the user designed, while graph nodes and metrics
             * remain evidence derived from an uploaded log.
             */
            route("/processes/{processId}/activities") {
                get {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@get
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId) == null) {
                        return@get
                    }

                    call.respond(activitiesRepository.listForProcess(processId).map { it.toResponse() })
                }

                put("/{activityId}") {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    val activityId = call.parameters["activityId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@put
                    }
                    if (activityId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de atividade inválido."))
                        return@put
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId) == null) {
                        return@put
                    }

                    val activity = activitiesRepository.findById(activityId)
                    if (activity == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Atividade não encontrada."))
                        return@put
                    }

                    activitiesRepository.linkToProcess(processId, activityId)
                    call.respond(activity.toResponse())
                }

                delete("/{activityId}") {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    val activityId = call.parameters["activityId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@delete
                    }
                    if (activityId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de atividade inválido."))
                        return@delete
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId) == null) {
                        return@delete
                    }

                    activitiesRepository.unlinkFromProcess(processId, activityId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/processes/{processId}/operations") {
                get {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@get
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@get
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId) == null) {
                        return@get
                    }

                    val activityNames = activitiesRepository.listAll().associate { it.id to it.name }
                    val operations = eventLogsRepository.findByProcessId(processId)
                        .flatMap { operationsRepository.listForEventLog(it.id) }
                        .map { it.toResponse(activityNames[it.activityId]) }

                    call.respond(operations)
                }
            }

            /**
             * UC7 — the process graph, derived from the latest parsed event log
             * of this process. Empty (not an error) while no log was ingested:
             * "nothing uploaded yet" is a state the canvas draws.
             */
            route("/processes/{processId}/graph") {
                get {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@get
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId) == null) {
                        return@get
                    }

                    call.respond(graphRepository.graph(processId))
                }
            }

            /** Variant tree behind the canvas's traces panel. */
            route("/processes/{processId}/traces") {
                get {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@get
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId) == null) {
                        return@get
                    }

                    call.respond(graphRepository.variants(processId))
                }
            }

            route("/processes/{processId}/analysis") {
                post {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@post
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId) == null) {
                        return@post
                    }

                    val series = analysisRepository.latestSeries(processId)
                    if (series == null) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse("Envie e processe um log antes de executar a análise."),
                        )
                        return@post
                    }
                    if (series.points.size < 32) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse(
                                "A análise exige ao menos 32 traces com duração válida; " +
                                    "este log possui ${series.points.size}.",
                            ),
                        )
                        return@post
                    }

                    val detection = try {
                        miningClient.detect(series.points.map { it.durationSeconds })
                    } catch (err: MiningAnalysisException) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse(err.message ?: "Não foi possível analisar os traces."),
                        )
                        return@post
                    } catch (err: MiningUnavailableException) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse(err.message ?: "Serviço de análise indisponível."),
                        )
                        return@post
                    }

                    val drifts = detection.drifts.mapNotNull { detected ->
                        val point = series.points.getOrNull(detected.index) ?: return@mapNotNull null
                        val before = series.points
                            .subList((detected.index - 16).coerceAtLeast(0), detected.index)
                            .map { it.durationSeconds }
                        val after = series.points
                            .subList(detected.index, (detected.index + 16).coerceAtMost(series.points.size))
                            .map { it.durationSeconds }
                        val beforeMean = before.averageOrZero()
                        val afterMean = after.averageOrZero()

                        AnalysisDriftResponse(
                            index = point.index,
                            traceId = point.traceId,
                            caseId = point.caseId,
                            durationSeconds = point.durationSeconds,
                            beforeMeanSeconds = beforeMean,
                            afterMeanSeconds = afterMean,
                            magnitudePercent = if (beforeMean == 0.0) {
                                0.0
                            } else {
                                (afterMean - beforeMean) * 100.0 / beforeMean
                            },
                            windowWidth = detected.width,
                            estimationSeconds = detected.estimation,
                        )
                    }

                    call.respond(
                        ProcessAnalysisResponse(
                            eventLog = series.eventLog.toResponse(),
                            method = detection.method,
                            delta = detection.delta,
                            traceCount = series.points.size,
                            points = series.points,
                            drifts = drifts,
                        ),
                    )
                }
            }

            route("/processes/{processId}/event-logs") {
                get {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@get
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId) == null) {
                        return@get
                    }

                    call.respond(eventLogsRepository.findByProcessId(processId).map { it.toResponse() })
                }

                /**
                 * UC4 — upload an event log into an existing process.
                 *
                 * The file goes to the Python mining service for parsing and
                 * comes back as distinct labels + traces; this route persists
                 * that result and hands the Manager the unmapped operations to
                 * resolve (UC5). The process graph stays unavailable until the
                 * mapping is confirmed — an unmapped log has no activities to
                 * draw nodes from.
                 */
                post {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@post
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId) == null) {
                        return@post
                    }

                    val upload = call.receiveUploadedFile()
                    if (upload == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nenhum arquivo enviado."))
                        return@post
                    }
                    val (fileName, bytes) = upload

                    val extension = fileName.substringAfterLast('.', "").lowercase()
                    if (extension !in setOf("xes", "csv")) {
                        call.respond(
                            HttpStatusCode.UnsupportedMediaType,
                            ErrorResponse("Envie um arquivo .xes ou .csv."),
                        )
                        return@post
                    }

                    val parsed = try {
                        miningClient.parse(fileName, bytes)
                    } catch (err: MiningParseException) {
                        // Keep the failed attempt on the process: a log the
                        // Manager could not parse is history worth showing.
                        ingestionRepository.recordFailure(
                            processId, fileName, extension, err.message ?: "Falha ao interpretar o arquivo.",
                        )
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(err.message ?: "Arquivo inválido."))
                        return@post
                    } catch (err: MiningUnavailableException) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(err.message ?: "Serviço indisponível."))
                        return@post
                    }

                    val ingested = ingestionRepository.ingest(processId, fileName, parsed)
                    val statsByLabel = parsed.events.associateBy { it.rawLabel }

                    val operations = operationsRepository.listForEventLog(ingested.eventLog.id).map { operation ->
                        val stats = statsByLabel[operation.rawLabel]
                        UnmappedOperationResponse(
                            id = operation.id.toString(),
                            rawLabel = operation.rawLabel,
                            occurrenceCount = operation.occurrenceCount,
                            caseCount = stats?.caseCount ?: 0,
                            meanDurationSeconds = stats?.meanDurationSeconds ?: 0.0,
                            // No catalog to match against yet — the Manager
                            // rewrites this before confirming.
                            suggestedActivityName = operation.rawLabel,
                            activityId = operation.activityId?.toString(),
                            mappingStatus = operation.mappingStatus,
                        )
                    }

                    call.respond(
                        HttpStatusCode.Created,
                        EventLogUploadResponse(
                            eventLog = ingested.eventLog.toResponse(),
                            operations = operations,
                        ),
                    )
                }
            }

            /**
             * UC5 — confirm the whole mapping at once. Partial confirmation is
             * deliberately not offered: the Manager reviews every operation in
             * the modal and approves the set.
             */
            route("/processes/{processId}/mapping") {
                post {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@post
                    }
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId) == null) {
                        return@post
                    }

                    val body = call.receive<ResolveMappingRequest>()
                    if (body.mappings.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nenhum mapeamento enviado."))
                        return@post
                    }

                    val blank = body.mappings.filter { it.activityName.isBlank() }
                    if (blank.isNotEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Toda atividade precisa de um nome (${blank.size} em branco)."),
                        )
                        return@post
                    }

                    val resolutions = body.mappings.mapNotNull { item ->
                        val operationId = runCatchingUuid(item.operationId) ?: return@mapNotNull null
                        MappingRepository.Resolution(
                            operationId = operationId,
                            activityName = item.activityName,
                            activityDescription = item.activityDescription,
                        )
                    }
                    if (resolutions.size != body.mappings.size) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de operação inválido no mapeamento."))
                        return@post
                    }

                    val result = mappingRepository.resolveAll(processId, resolutions)
                    val activityNames = result.activities.associate { it.id to it.name }

                    call.respond(
                        ResolveMappingResponse(
                            mappedCount = result.operations.size,
                            activities = result.activities.map { it.toResponse() },
                            operations = result.operations.map { it.toResponse(activityNames[it.activityId]) },
                        ),
                    )
                }
            }

            route("/operations/{id}/map") {
                post {
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de operação inválido."))
                        return@post
                    }

                    val body = call.receive<MapOperationRequest>()
                    val activityId = runCatchingUuid(body.activityId)
                    if (activityId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de atividade inválido."))
                        return@post
                    }

                    val updated = operationsRepository.mapToActivity(id, activityId)
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Operação não encontrada."))
                        return@post
                    }
                    call.respond(updated.toResponse())
                }
            }

            route("/operations/{id}/unmap") {
                post {
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de operação inválido."))
                        return@post
                    }

                    val updated = operationsRepository.unmap(id)
                    if (updated == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Operação não encontrada."))
                        return@post
                    }
                    call.respond(updated.toResponse())
                }
            }
        }
    }
}

private fun List<Double>.averageOrZero(): Double =
    if (isEmpty()) 0.0 else average()

private fun runCatchingUuid(value: String): UUID? = try {
    UUID.fromString(value)
} catch (_: IllegalArgumentException) {
    null
}

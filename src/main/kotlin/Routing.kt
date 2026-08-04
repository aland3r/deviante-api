package com.deviante

import com.deviante.dto.CreateActivityRequest
import com.deviante.dto.AnalysisDriftResponse
import com.deviante.dto.AnalysisScopeResponse
import com.deviante.dto.CreateAnalysisRequest
import com.deviante.dto.DeleteProcessRequest
import com.deviante.dto.ErrorResponse
import com.deviante.dto.EventLogUploadResponse
import com.deviante.dto.MapOperationRequest
import com.deviante.dto.MonitoringDetectRequest
import com.deviante.dto.AnalysisProvenanceResponse
import com.deviante.dto.AppendReadingsRequest
import com.deviante.dto.EquipmentAnalysisResponse
import com.deviante.dto.EquipmentRequest
import com.deviante.dto.EquipmentParameterRequest
import com.deviante.dto.MaintenanceScheduleRequest
import com.deviante.dto.MonitoringParameterRequest
import com.deviante.dto.MonitoringRequest
import com.deviante.dto.CreateMonitoringRequest
import com.deviante.dto.toMonitoringRequest
import com.deviante.dto.RecommendationRequest
import com.deviante.dto.RunEquipmentAnalysisRequest
import com.deviante.dto.ReadingImportResponse
import com.deviante.dto.ResolveMappingRequest
import com.deviante.dto.ResolveMappingResponse
import com.deviante.dto.RunAnalysisRequest
import com.deviante.dto.UpdateFilterRequest
import com.deviante.dto.RenameProcessRequest
import com.deviante.dto.ProcessAnalysisResponse
import com.deviante.dto.UnmappedOperationResponse
import com.deviante.dto.UpdateActivityRequest
import com.deviante.dto.UpdateManagerRequest
import com.deviante.dto.UpdateProcessRequest
import com.deviante.dto.validateProcessDeletion
import com.deviante.dto.toResponse
import com.deviante.dto.toSummaryResponse
import com.deviante.model.ProcessRecord
import com.deviante.repository.ActivitiesRepository
import com.deviante.repository.AnalysisRepository
import com.deviante.repository.AnalysisSeriesException
import com.deviante.repository.EventLogIngestionRepository
import com.deviante.repository.EventLogsRepository
import com.deviante.repository.ManagerRepository
import com.deviante.repository.MappingRepository
import com.deviante.repository.MaintenanceRepository
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.OffsetDateTime
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
    val maintenanceRepository = MaintenanceRepository()
    val miningClient = configureMiningClient()
    val routeJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

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

            get("/operations") {
                val user = call.requireSupabaseUser(authClient) ?: return@get
                val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
                call.respond(
                    operationsRepository.listCatalogForManager(manager.id).map { catalog ->
                        catalog.operation.toResponse(
                            activityName = catalog.activityName,
                            processId = catalog.processId.toString(),
                            processName = catalog.processName,
                            eventLogName = catalog.eventLogName,
                            equipmentIds = catalog.equipment.map { it.id.toString() },
                            equipmentNames = catalog.equipment.map { it.name },
                        )
                    },
                )
            }

            route("/equipment") {
                get {
                    val user = call.requireSupabaseUser(authClient) ?: return@get
                    val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
                    call.respond(maintenanceRepository.listEquipment(manager.id))
                }
                post {
                    val user = call.requireSupabaseUser(authClient) ?: return@post
                    val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
                    val body = call.receive<EquipmentRequest>()
                    if (body.name.isBlank()) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nome do equipamento é obrigatório.")); return@post }
                    call.respond(HttpStatusCode.Created, maintenanceRepository.createEquipment(manager.id, body))
                }
                get("/{id}") {
                    call.requireSupabaseUser(authClient) ?: return@get
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    val item = id?.let(maintenanceRepository::findEquipment)
                    if (item == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Equipamento não encontrado.")) else call.respond(item)
                }
                put("/{id}") {
                    call.requireSupabaseUser(authClient) ?: return@put
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    val body = call.receive<EquipmentRequest>()
                    if (id == null || body.name.isBlank()) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("Equipamento ou nome inválido.")); return@put }
                    val item = maintenanceRepository.updateEquipment(id, body)
                    if (item == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Equipamento não encontrado.")) else call.respond(item)
                }
                delete("/{id}") {
                    call.requireSupabaseUser(authClient) ?: return@delete
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null || !maintenanceRepository.deleteEquipment(id)) call.respond(HttpStatusCode.NotFound, ErrorResponse("Equipamento não encontrado."))
                    else call.respond(HttpStatusCode.NoContent)
                }
                get("/{id}/parameters") {
                    call.requireSupabaseUser(authClient) ?: return@get
                    val equipmentId = call.parameters["id"]?.let(::runCatchingUuid)
                    if (equipmentId == null) call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de equipamento inválido."))
                    else call.respond(maintenanceRepository.listParametersForEquipment(equipmentId))
                }
                post("/{id}/parameters") {
                    call.requireSupabaseUser(authClient) ?: return@post
                    val equipmentId = call.parameters["id"]?.let(::runCatchingUuid)
                    val body = call.receive<EquipmentParameterRequest>()
                    val monitoringId = equipmentId?.let(maintenanceRepository::firstMonitoringForEquipment)
                    if (equipmentId == null || body.name.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Equipamento e nome do parâmetro são obrigatórios.")); return@post
                    }
                    if (monitoringId == null) {
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Associe o equipamento a um monitoramento antes de criar parâmetros.")); return@post
                    }
                    val created = maintenanceRepository.createParameter(
                        monitoringId,
                        MonitoringParameterRequest(equipmentId.toString(), body.name, body.unit, body.description),
                    )
                    if (created == null) call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Não foi possível criar o parâmetro."))
                    else call.respond(HttpStatusCode.Created, created)
                }
                delete("/{id}/parameters/{parameterId}") {
                    call.requireSupabaseUser(authClient) ?: return@delete
                    val equipmentId = call.parameters["id"]?.let(::runCatchingUuid)
                    val parameterId = call.parameters["parameterId"]?.let(::runCatchingUuid)
                    if (equipmentId == null || parameterId == null || !maintenanceRepository.deleteParameter(equipmentId, parameterId)) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Parâmetro não encontrado."))
                    } else call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/readings") {
                    call.requireSupabaseUser(authClient) ?: return@post
                    val equipmentId = call.parameters["id"]?.let(::runCatchingUuid)
                    val upload = call.receiveUploadedFile()
                    val monitoringId = equipmentId?.let(maintenanceRepository::firstMonitoringForEquipment)
                    if (equipmentId == null || upload == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Equipamento e arquivo são obrigatórios.")); return@post
                    }
                    if (monitoringId == null) {
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Associe o equipamento a um monitoramento antes de importar leituras.")); return@post
                    }
                    val (fileName, content) = upload
                    var parameterCount = 0
                    var readingCount = 0
                    if (fileName.lowercase().endsWith(".xes")) {
                        val parsed = try { miningClient.parse(fileName, content) }
                        catch (err: MiningParseException) { call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(err.message ?: "Log inválido.")); return@post }
                        catch (err: MiningUnavailableException) { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(err.message ?: "Serviço indisponível.")); return@post }
                        val labels = parsed.traces.flatMap { trace -> trace.events.map { it.rawLabel } }.distinct()
                        for (label in labels) {
                            val parameter = maintenanceRepository.findParameterForEquipmentByName(equipmentId, label)
                                ?: maintenanceRepository.createParameter(
                                    monitoringId,
                                    MonitoringParameterRequest(
                                        equipmentId.toString(), label,
                                        if (label.equals("Equipment_Failure", true)) "binary" else "seconds",
                                        "Importado de $fileName",
                                    ),
                                ) ?: continue
                            parameterCount++
                            val now = OffsetDateTime.now()
                            val rows = parsed.traces.mapIndexed { index, trace ->
                                val events = trace.events.filter { it.rawLabel == label }
                                val value = if (label.equals("Equipment_Failure", true)) {
                                    if (events.isEmpty()) 0.0 else 1.0
                                } else events.sumOf { it.durationSeconds ?: 0.0 }
                                val observedAt = trace.startedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                                    ?: now.plusSeconds(index.toLong())
                                com.deviante.dto.ReadingInput(observedAt.toString(), value) to observedAt
                            }
                            maintenanceRepository.appendReadings(UUID.fromString(parameter.id), rows)
                            readingCount += rows.size
                        }
                    } else {
                        val text = content.toString(Charsets.UTF_8)
                        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
                        if (lines.size < 2) {
                            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("CSV sem leituras.")); return@post
                        }
                        val delimiter = if (lines.first().contains(';')) ';' else ','
                        val header = lines.first().split(delimiter).map { it.trim().lowercase().replace("_", "") }
                        val parameterIndex = header.indexOfFirst { it in setOf("parameter", "parametro", "activity") }
                        val timeIndex = header.indexOfFirst { it in setOf("observedat", "timestamp", "time") }
                        val valueIndex = header.indexOf("value")
                        val existing = maintenanceRepository.listParametersForEquipment(equipmentId)
                        if (valueIndex < 0 || (parameterIndex < 0 && existing.size != 1)) {
                            call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Use CSV parameter,observedAt,value; sem parameter é permitido quando há um único parâmetro.")); return@post
                        }
                        val grouped = linkedMapOf<String, MutableList<Pair<com.deviante.dto.ReadingInput, OffsetDateTime>>>()
                        lines.drop(1).forEachIndexed { index, line ->
                            val cells = line.split(delimiter).map(String::trim)
                            if (valueIndex >= cells.size) return@forEachIndexed
                            val name = if (parameterIndex >= 0 && parameterIndex < cells.size) cells[parameterIndex] else existing.single().name
                            val value = cells[valueIndex].replace(',', '.').toDoubleOrNull() ?: return@forEachIndexed
                            val observedAt = if (timeIndex >= 0 && timeIndex < cells.size) runCatching { OffsetDateTime.parse(cells[timeIndex]) }.getOrNull() else null
                            val instant = observedAt ?: OffsetDateTime.now().plusSeconds(index.toLong())
                            grouped.getOrPut(name) { mutableListOf() }.add(com.deviante.dto.ReadingInput(instant.toString(), value) to instant)
                        }
                        for ((name, rows) in grouped) {
                            val parameter = maintenanceRepository.findParameterForEquipmentByName(equipmentId, name) ?: continue
                            maintenanceRepository.appendReadings(UUID.fromString(parameter.id), rows)
                            parameterCount++; readingCount += rows.size
                        }
                    }
                    call.respond(HttpStatusCode.Created, ReadingImportResponse(fileName, parameterCount, readingCount))
                }
                get("/{id}/analyses") {
                    call.requireSupabaseUser(authClient) ?: return@get
                    val equipmentId = call.parameters["id"]?.let(::runCatchingUuid)
                    if (equipmentId == null) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de equipamento inválido.")); return@get }
                    val responses = maintenanceRepository.listAnalyses(equipmentId).map { stored ->
                        EquipmentAnalysisResponse(
                            id = stored.id.toString(), equipmentId = stored.equipmentId.toString(), monitoringId = stored.monitoringId.toString(),
                            parameterId = stored.parameterId.toString(), inputSha256 = stored.inputSha256,
                            result = routeJson.decodeFromString(stored.resultJson), provenance = routeJson.decodeFromString(stored.provenanceJson),
                            rulValue = stored.rulValue, rulUnit = stored.rulUnit, failureProbability = stored.failureProbability,
                            failureHorizonValue = stored.failureHorizonValue, failureHorizonUnit = stored.failureHorizonUnit,
                            modelVersion = stored.modelVersion, recommendation = stored.recommendation, createdAt = stored.createdAt.toString(),
                        )
                    }
                    call.respond(responses)
                }
                post("/{id}/analyses") {
                    val user = call.requireSupabaseUser(authClient) ?: return@post
                    val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
                    val equipmentId = call.parameters["id"]?.let(::runCatchingUuid)
                    val body = call.receive<RunEquipmentAnalysisRequest>()
                    val parameterId = runCatchingUuid(body.parameterId)
                    if (equipmentId == null || parameterId == null || maintenanceRepository.findEquipment(equipmentId) == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Equipamento ou parâmetro inválido.")); return@post
                    }
                    val parameter = maintenanceRepository.findParameter(parameterId)
                    if (parameter == null || parameter.equipmentId != equipmentId.toString()) {
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("O parâmetro não pertence a este equipamento.")); return@post
                    }
                    val series = maintenanceRepository.readingSeries(parameterId)
                    if (series == null || series.values.size < 2) {
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("A análise exige ao menos duas leituras persistidas.")); return@post
                    }
                    if (!maintenanceRepository.monitoringContainsEquipment(series.monitoringId, equipmentId)) {
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("O monitoramento não está associado ao equipamento.")); return@post
                    }
                    val delta = body.delta ?: 0.002
                    if (delta <= 0 || delta >= 1 || body.treatment !in setOf("raw", "treated")) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Parâmetros ADWIN inválidos.")); return@post
                    }
                    val detection = try { miningClient.detect(series.values, delta, body.treatment) }
                    catch (err: MiningAnalysisException) { call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(err.message ?: "Série inválida.")); return@post }
                    catch (err: MiningUnavailableException) { call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(err.message ?: "Serviço indisponível.")); return@post }
                    val hash = MessageDigest.getInstance("SHA-256").digest(series.values.joinToString("\n").toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    val driftIndexes = detection.drifts.map { it.index }.toSet()
                    val predictiveHistory = maintenanceRepository.predictiveSignals(equipmentId)
                        .mapIndexed { index, row ->
                            MaintenanceObservationRequest(
                                machineOperating = row.machineOperating,
                                rawMaterialLoading = row.rawMaterialLoading,
                                shortDowntime = row.shortDowntime,
                                driftDetected = index in driftIndexes,
                            )
                        }
                    var predictionUnavailable: String? = null
                    val prediction = if (predictiveHistory.size >= 30) {
                        try { miningClient.predictMaintenance(predictiveHistory) }
                        catch (err: MiningAnalysisException) { predictionUnavailable = err.message; null }
                        catch (err: MiningUnavailableException) { predictionUnavailable = err.message; null }
                    } else {
                        predictionUnavailable = "RUL exige Machine_Operating, Raw_Material_Loading e Short_Downtime com ao menos 30 traces."
                        null
                    }
                    val recommendation = when {
                        prediction == null -> predictionUnavailable
                        prediction.failureProbability >= 0.70 || prediction.rulTraces <= 10 -> "Priorizar inspeção e planejar manutenção antes do RUL estimado."
                        prediction.failureProbability >= 0.40 || prediction.rulTraces <= 20 -> "Agendar inspeção preventiva e acompanhar a próxima janela."
                        else -> "Manter monitoramento; não há intervenção imediata indicada."
                    }
                    val provenance = AnalysisProvenanceResponse(
                        sourceKind = "monitoring_parameter", equipmentId = equipmentId.toString(), monitoringId = series.monitoringId.toString(),
                        parameterId = parameterId.toString(), firstObservedAt = series.firstObservedAt?.toString(), lastObservedAt = series.lastObservedAt?.toString(),
                        engineVersion = body.provenance.engineVersion, modelVersion = prediction?.provenance?.modelVersion,
                        datasetVersion = body.provenance.datasetVersion, codeRevision = body.provenance.codeRevision,
                        predictionSource = prediction?.provenance?.source ?: predictionUnavailable,
                    )
                    val stored = maintenanceRepository.saveAnalysis(
                        equipmentId, series.monitoringId, parameterId, detection.method, detection.delta, detection.treatment,
                        detection.observationCount, hash, routeJson.encodeToString(detection), routeJson.encodeToString(provenance),
                        prediction?.rulTraces, "traces", prediction?.failureProbability,
                        prediction?.failureHorizonTraces?.toDouble(), prediction?.let { "traces" },
                        prediction?.provenance?.modelVersion, recommendation,
                    )
                    if (prediction != null && recommendation != null) {
                        maintenanceRepository.createRecommendation(manager.id, equipmentId, stored.id, RecommendationRequest(recommendation))
                    }
                    call.respond(HttpStatusCode.Created, EquipmentAnalysisResponse(
                        stored.id.toString(), equipmentId.toString(), series.monitoringId.toString(), parameterId.toString(), hash,
                        detection, provenance, prediction?.rulTraces, "traces", prediction?.failureProbability,
                        prediction?.failureHorizonTraces?.toDouble(), prediction?.let { "traces" },
                        prediction?.provenance?.modelVersion, recommendation, stored.createdAt.toString(),
                    ))
                }
            }

            route("/processes/{processId}/equipment") {
                get {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null || call.requireProcess(authClient, managerRepository, processRepository, processId) == null) return@get
                    call.respond(maintenanceRepository.listProcessEquipment(processId))
                }
                put("/{equipmentId}") {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    val equipmentId = call.parameters["equipmentId"]?.let(::runCatchingUuid)
                    if (processId == null || equipmentId == null || call.requireProcess(authClient, managerRepository, processRepository, processId) == null) return@put
                    if (!maintenanceRepository.linkProcessEquipment(processId, equipmentId)) call.respond(HttpStatusCode.NotFound, ErrorResponse("Equipamento não encontrado."))
                    else call.respond(HttpStatusCode.NoContent)
                }
                delete("/{equipmentId}") {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    val equipmentId = call.parameters["equipmentId"]?.let(::runCatchingUuid)
                    if (processId == null || equipmentId == null || call.requireProcess(authClient, managerRepository, processRepository, processId) == null) return@delete
                    if (!maintenanceRepository.unlinkProcessEquipment(processId, equipmentId)) call.respond(HttpStatusCode.NotFound, ErrorResponse("Associação não encontrada."))
                    else call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/monitorings") {
                get { val u = call.requireSupabaseUser(authClient) ?: return@get; managerRepository.findOrCreateForSupabaseUser(u.id,u.email,u.fullNameHint); call.respond(maintenanceRepository.listMonitorings()) }
                post {
                    val u = call.requireSupabaseUser(authClient) ?: return@post; val m = managerRepository.findOrCreateForSupabaseUser(u.id,u.email,u.fullNameHint)
                    val body = call.receive<CreateMonitoringRequest>().toMonitoringRequest()
                    call.respond(HttpStatusCode.Created, maintenanceRepository.createMonitoring(m.id, body))
                }
                get("/{id}") { call.requireSupabaseUser(authClient) ?: return@get; val item = call.parameters["id"]?.let(::runCatchingUuid)?.let(maintenanceRepository::findMonitoring); if(item==null) call.respond(HttpStatusCode.NotFound,ErrorResponse("Monitoramento não encontrado.")) else call.respond(item) }
                put("/{id}") { call.requireSupabaseUser(authClient) ?: return@put; val id=call.parameters["id"]?.let(::runCatchingUuid); val body=call.receive<MonitoringRequest>(); val item=id?.let{maintenanceRepository.updateMonitoring(it,body)}; if(item==null) call.respond(HttpStatusCode.NotFound,ErrorResponse("Monitoramento não encontrado.")) else call.respond(item) }
                delete("/{id}") { call.requireSupabaseUser(authClient) ?: return@delete; val id=call.parameters["id"]?.let(::runCatchingUuid); if(id==null||!maintenanceRepository.deleteMonitoring(id)) call.respond(HttpStatusCode.NotFound,ErrorResponse("Monitoramento não encontrado.")) else call.respond(HttpStatusCode.NoContent) }
                get("/{id}/equipment") { call.requireSupabaseUser(authClient) ?: return@get; val id=call.parameters["id"]?.let(::runCatchingUuid); if(id==null) call.respond(HttpStatusCode.BadRequest,ErrorResponse("ID inválido.")) else call.respond(maintenanceRepository.listMonitoringEquipment(id)) }
                post("/{id}/equipment") {
                    val u=call.requireSupabaseUser(authClient)?:return@post
                    val m=managerRepository.findOrCreateForSupabaseUser(u.id,u.email,u.fullNameHint)
                    val id=call.parameters["id"]?.let(::runCatchingUuid)
                    val body=call.receive<EquipmentRequest>()
                    if(id==null||body.name.isBlank()) { call.respond(HttpStatusCode.BadRequest,ErrorResponse("Monitoramento e nome do equipamento são obrigatórios.")); return@post }
                    if(maintenanceRepository.findMonitoring(id)==null) { call.respond(HttpStatusCode.NotFound,ErrorResponse("Monitoramento não encontrado.")); return@post }
                    val equipment=maintenanceRepository.createEquipment(m.id,body)
                    maintenanceRepository.linkMonitoringEquipment(id,java.util.UUID.fromString(equipment.id))
                    call.respond(HttpStatusCode.Created,equipment)
                }
                put("/{id}/equipment/{equipmentId}") { call.requireSupabaseUser(authClient) ?: return@put; val id=call.parameters["id"]?.let(::runCatchingUuid); val eid=call.parameters["equipmentId"]?.let(::runCatchingUuid); if(id==null||eid==null||!maintenanceRepository.linkMonitoringEquipment(id,eid)) call.respond(HttpStatusCode.NotFound,ErrorResponse("Monitoramento ou equipamento não encontrado.")) else call.respond(HttpStatusCode.NoContent) }
                delete("/{id}/equipment/{equipmentId}") { call.requireSupabaseUser(authClient) ?: return@delete; val id=call.parameters["id"]?.let(::runCatchingUuid); val eid=call.parameters["equipmentId"]?.let(::runCatchingUuid); if(id==null||eid==null||!maintenanceRepository.unlinkMonitoringEquipment(id,eid)) call.respond(HttpStatusCode.NotFound,ErrorResponse("Associação não encontrada.")) else call.respond(HttpStatusCode.NoContent) }
                get("/{id}/parameters") { call.requireSupabaseUser(authClient) ?: return@get; val id=call.parameters["id"]?.let(::runCatchingUuid); if(id==null) call.respond(HttpStatusCode.BadRequest,ErrorResponse("ID inválido.")) else call.respond(maintenanceRepository.listParameters(id)) }
                post("/{id}/parameters") {
                    call.requireSupabaseUser(authClient) ?: return@post; val id=call.parameters["id"]?.let(::runCatchingUuid); val body=call.receive<MonitoringParameterRequest>()
                    if(id==null||body.name.isBlank()||runCatchingUuid(body.equipmentId)==null) { call.respond(HttpStatusCode.BadRequest,ErrorResponse("Monitoramento, equipamento ou parâmetro inválido.")); return@post }
                    val item=maintenanceRepository.createParameter(id,body); if(item==null) call.respond(HttpStatusCode.UnprocessableEntity,ErrorResponse("Associe o equipamento ao monitoramento antes de criar o parâmetro.")) else call.respond(HttpStatusCode.Created,item)
                }
            }

            route("/monitoring-parameters/{id}/readings") {
                get { call.requireSupabaseUser(authClient) ?: return@get; val id=call.parameters["id"]?.let(::runCatchingUuid); if(id==null) call.respond(HttpStatusCode.BadRequest,ErrorResponse("ID inválido.")) else call.respond(maintenanceRepository.listReadings(id)) }
                post {
                    call.requireSupabaseUser(authClient) ?: return@post; val id=call.parameters["id"]?.let(::runCatchingUuid); val body=call.receive<AppendReadingsRequest>()
                    if(id==null||maintenanceRepository.findParameter(id)==null||body.readings.isEmpty()) { call.respond(HttpStatusCode.BadRequest,ErrorResponse("Parâmetro e leituras são obrigatórios.")); return@post }
                    val parsed=body.readings.mapNotNull { input -> runCatching { input to OffsetDateTime.parse(input.observedAt) }.getOrNull() }
                    if(parsed.size!=body.readings.size||body.readings.any{!it.value.isFinite()}) { call.respond(HttpStatusCode.BadRequest,ErrorResponse("Leitura, data ou valor inválido.")); return@post }
                    call.respond(HttpStatusCode.Created,maintenanceRepository.appendReadings(id,parsed))
                }
            }

            route("/equipment-analyses/{id}") {
                get {
                    call.requireSupabaseUser(authClient) ?: return@get; val id=call.parameters["id"]?.let(::runCatchingUuid); val stored=id?.let(maintenanceRepository::findAnalysis)
                    if(stored==null) { call.respond(HttpStatusCode.NotFound,ErrorResponse("Análise não encontrada.")); return@get }
                    call.respond(EquipmentAnalysisResponse(stored.id.toString(),stored.equipmentId.toString(),stored.monitoringId.toString(),stored.parameterId.toString(),stored.inputSha256,routeJson.decodeFromString(stored.resultJson),routeJson.decodeFromString(stored.provenanceJson),stored.rulValue,stored.rulUnit,stored.failureProbability,stored.failureHorizonValue,stored.failureHorizonUnit,stored.modelVersion,stored.recommendation,stored.createdAt.toString()))
                }
                post("/recommendations") {
                    val u=call.requireSupabaseUser(authClient)?:return@post; val m=managerRepository.findOrCreateForSupabaseUser(u.id,u.email,u.fullNameHint); val id=call.parameters["id"]?.let(::runCatchingUuid); val stored=id?.let(maintenanceRepository::findAnalysis); val body=call.receive<RecommendationRequest>()
                    if(stored==null||body.action.isBlank()) { call.respond(HttpStatusCode.BadRequest,ErrorResponse("Análise e ação são obrigatórias.")); return@post }
                    call.respond(HttpStatusCode.Created,maintenanceRepository.createRecommendation(m.id,stored.equipmentId,stored.id,body)!!)
                }
            }

            route("/schedules") {
                get {
                    val u=call.requireSupabaseUser(authClient)?:return@get
                    managerRepository.findOrCreateForSupabaseUser(u.id,u.email,u.fullNameHint)
                    val equipmentId = call.request.queryParameters["equipmentId"]?.let(::runCatchingUuid)
                    call.respond(maintenanceRepository.listSchedules(equipmentId))
                }
                post {
                    val u=call.requireSupabaseUser(authClient)?:return@post; val m=managerRepository.findOrCreateForSupabaseUser(u.id,u.email,u.fullNameHint); val body=call.receive<MaintenanceScheduleRequest>()
                    val start=runCatching{OffsetDateTime.parse(body.scheduledStart)}.getOrNull(); val end=body.scheduledEnd?.let{runCatching{OffsetDateTime.parse(it)}.getOrNull()}
                    if(body.title.isBlank()||start==null||(body.scheduledEnd!=null&&end==null)||(end!=null&&end<start)) { call.respond(HttpStatusCode.BadRequest,ErrorResponse("Agenda de manutenção inválida.")); return@post }
                    val item=runCatching{maintenanceRepository.createSchedule(m.id,body)}.getOrNull(); if(item==null) call.respond(HttpStatusCode.UnprocessableEntity,ErrorResponse("Referências da agenda inválidas.")) else call.respond(HttpStatusCode.Created,item)
                }
                put("/{id}") { call.requireSupabaseUser(authClient)?:return@put; val id=call.parameters["id"]?.let(::runCatchingUuid); val body=call.receive<MaintenanceScheduleRequest>(); val item=runCatching{id?.let{maintenanceRepository.updateSchedule(it,body)}}.getOrNull(); if(item==null) call.respond(HttpStatusCode.BadRequest,ErrorResponse("Agenda inválida ou não encontrada.")) else call.respond(item) }
                delete("/{id}") { call.requireSupabaseUser(authClient)?:return@delete; val id=call.parameters["id"]?.let(::runCatchingUuid); if(id==null||!maintenanceRepository.deleteSchedule(id)) call.respond(HttpStatusCode.NotFound,ErrorResponse("Agenda não encontrada.")) else call.respond(HttpStatusCode.NoContent) }
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
                    val process = call.requireProcess(authClient, managerRepository, processRepository, processId)
                        ?: return@post

                    val analysisId = call.request.queryParameters["analysisId"]?.let(::runCatchingUuid)

                    // UC13 subtractive filter. An empty body keeps the historical
                    // defaults: whole-trace duration, treated series, delta 0.002.
                    val body = call.receive<RunAnalysisRequest>()

                    val treatment = body.treatment.lowercase()
                    if (treatment !in setOf("raw", "treated")) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Tratamento inválido: use 'raw' ou 'treated'."),
                        )
                        return@post
                    }

                    val delta = body.delta ?: 0.002
                    if (delta <= 0.0 || delta >= 1.0) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("δ inválido: informe um número entre 0 e 1."),
                        )
                        return@post
                    }

                    val excludedActivityIds = try {
                        body.excludedActivityIds.map { UUID.fromString(it) }.toSet()
                    } catch (err: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de atividade inválido no filtro."))
                        return@post
                    }
                    val excludedTraceIds = try {
                        body.excludedTraceIds.map { UUID.fromString(it) }.toSet()
                    } catch (err: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de trace inválido no filtro."))
                        return@post
                    }

                    val series = try {
                        analysisRepository.latestSeries(processId, excludedActivityIds, excludedTraceIds)
                    } catch (err: AnalysisSeriesException) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse(err.message ?: "Não foi possível montar a série de análise."),
                        )
                        return@post
                    }
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
                        miningClient.detect(
                            values = series.points.map { it.durationSeconds },
                            delta = delta,
                            treatment = treatment,
                        )
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
                        val anomalyStartPoint = series.points.getOrNull(detected.anomalyStartIndex)
                        val anomalyStart = anomalyStartPoint?.index ?: point.index

                        // The P-F interval in calendar time. Null when the log
                        // carried no usable timestamps, which is honest: better
                        // an absent date than one inferred from row order.
                        val detectedAt = point.startedAt
                        val anomalyStartedAt = anomalyStartPoint?.startedAt
                        val delaySeconds = if (detectedAt != null && anomalyStartedAt != null) {
                            runCatching {
                                java.time.Duration.between(
                                    OffsetDateTime.parse(anomalyStartedAt),
                                    OffsetDateTime.parse(detectedAt),
                                ).toMillis() / 1000.0
                            }.getOrNull()
                        } else {
                            null
                        }
                        val comparisonWidth = detected.width.toInt().coerceAtLeast(1)
                        val before = detection.processedValues.subList(
                            (detected.anomalyStartIndex - comparisonWidth).coerceAtLeast(0),
                            detected.anomalyStartIndex,
                        )
                        val after = detection.processedValues.subList(
                            detected.anomalyStartIndex,
                            (detected.index + 1).coerceAtMost(detection.processedValues.size),
                        )
                        val beforeMean = before.averageOrZero()
                        val afterMean = after.averageOrZero()

                        AnalysisDriftResponse(
                            index = point.index,
                            anomalyStartIndex = anomalyStart,
                            detectionDelayTraces = point.index - anomalyStart,
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
                            detectedAt = detectedAt,
                            anomalyStartedAt = anomalyStartedAt,
                            detectionDelaySeconds = delaySeconds,
                        )
                    }

                    // The series is whole-trace duration under a subtractive
                    // filter; the applied exclusions travel on the response so
                    // the client reflects and persists exactly what ran.
                    val scope = AnalysisScopeResponse(
                        kind = if (excludedActivityIds.isEmpty()) "process" else "activity",
                    )

                    val response = ProcessAnalysisResponse(
                        eventLog = series.eventLog.toResponse(),
                        method = detection.method,
                        delta = detection.delta,
                        treatment = detection.treatment,
                        scope = scope,
                        traceCount = series.points.size,
                        smoothingWindow = detection.smoothingWindow,
                        processedValues = detection.processedValues,
                        outlierIndexes = detection.outlierIndices.mapNotNull { index ->
                            series.points.getOrNull(index)?.index
                        },
                        points = series.points,
                        drifts = drifts,
                        excludedActivityIds = excludedActivityIds.map { it.toString() },
                        excludedTraceIds = excludedTraceIds.map { it.toString() },
                    )

                    val saved = analysisRepository.saveRun(
                        processId = processId,
                        processName = process.name.ifBlank { "Processo sem nome" },
                        analysisId = analysisId,
                        response = response,
                    )

                    call.respond(
                        response.copy(
                            id = saved.id.toString(),
                            name = saved.name,
                            processId = processId.toString(),
                            processName = saved.processName,
                        ),
                    )
                }
            }

            route("/analyses") {
                get {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@get
                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    call.respond(analysisRepository.listSummaries().map { it.toSummaryResponse() })
                }

                post {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@post
                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val body = call.receive<CreateAnalysisRequest>()
                    val processId = runCatchingUuid(body.processId)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@post
                    }
                    val process = call.requireProcess(authClient, managerRepository, processRepository, processId)
                        ?: return@post
                    val stub = analysisRepository.createStub(
                        processId = processId,
                        processName = process.name.ifBlank { "Processo sem nome" },
                        name = body.name,
                    )
                    call.respond(HttpStatusCode.Created, stub.toSummaryResponse())
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
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de análise inválido."))
                        return@get
                    }
                    val record = analysisRepository.findById(id)
                    if (record == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Análise não encontrada."))
                        return@get
                    }
                    val saved = analysisRepository.decodeResult(record)
                    if (saved != null) {
                        call.respond(saved)
                        return@get
                    }
                    call.respond(record.toSummaryResponse())
                }

                // Persist the Manager's subtractive filter so reopening an
                // analysis continues where they left off, without recomputing.
                put("/{id}/filter") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@put
                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de análise inválido."))
                        return@put
                    }
                    val body = call.receive<UpdateFilterRequest>()
                    val updated = analysisRepository.updateFilter(
                        id,
                        body.excludedActivityIds,
                        body.excludedTraceIds,
                    )
                    if (!updated) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("Análise sem resultado para atualizar."),
                        )
                        return@put
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            /**
             * Stateless IPDD/ADWIN over a machine-parameter series. The
             * monitoring registry lives on the client in v1.0, so this route
             * persists nothing — it authenticates, validates, and proxies the
             * series to the mining service, keeping the rule that the browser
             * never talks to the Python service directly.
             */
            route("/monitoring") {
                post("/detect") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@post
                    managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )

                    val body = call.receive<MonitoringDetectRequest>()
                    if (body.values.size < 2) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse("A análise exige ao menos duas observações."),
                        )
                        return@post
                    }

                    val treatment = body.treatment.lowercase()
                    if (treatment !in setOf("raw", "treated")) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Tratamento inválido: use 'raw' ou 'treated'."),
                        )
                        return@post
                    }

                    val delta = body.delta ?: 0.002
                    if (delta <= 0.0 || delta >= 1.0) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("δ inválido: informe um número entre 0 e 1."),
                        )
                        return@post
                    }

                    val detection = try {
                        miningClient.detect(values = body.values, delta = delta, treatment = treatment)
                    } catch (err: MiningAnalysisException) {
                        call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse(err.message ?: "Não foi possível analisar a série."),
                        )
                        return@post
                    } catch (err: MiningUnavailableException) {
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            ErrorResponse(err.message ?: "Serviço de análise indisponível."),
                        )
                        return@post
                    }

                    call.respond(detection)
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

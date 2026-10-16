package com.deviante

import com.deviante.dto.CreateActivityRequest
import com.deviante.dto.MaintenanceScheduleResponse
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
import com.deviante.dto.CreateTraceNoteRequest
import com.deviante.dto.SCHEDULE_STATUSES
import com.deviante.dto.ScheduleStatusRequest
import com.deviante.dto.VISIBILITY_OBJECT_TYPES
import com.deviante.dto.VisibilityRequest
import com.deviante.dto.VisibilityResponse
import com.deviante.dto.traceNoteError
import com.deviante.dto.validateProcessDeletion
import com.deviante.dto.toResponse
import com.deviante.dto.toSummaryResponse
import com.deviante.model.ManagerRecord
import com.deviante.variability.ClientVariant
import com.deviante.variability.Feature
import com.deviante.model.ProcessRecord
import com.deviante.repository.ActivitiesRepository
import com.deviante.repository.ActivityDeletion
import com.deviante.repository.AnalysisRepository
import com.deviante.repository.AnalysisSeriesException
import com.deviante.repository.BusinessRepository
import com.deviante.repository.EventLogIngestionRepository
import com.deviante.repository.EventLogsRepository
import com.deviante.repository.ManagerRepository
import com.deviante.repository.MappingRepository
import com.deviante.repository.MaintenanceRepository
import com.deviante.repository.OperationsRepository
import com.deviante.repository.ProcessGraphRepository
import com.deviante.repository.ProcessRepository
import com.deviante.repository.TraceNotesRepository
import com.deviante.repository.VisibilityRepository
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
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

internal val visibilityRepository = VisibilityRepository()
internal val scheduleLookup = MaintenanceRepository()

class ProcessAccess(val process: ProcessRecord, val manager: ManagerRecord)

/**
 * Pode o usuário ver o processo? Empresa no escopo; o técnico só vê processo
 * com agenda de manutenção da sua empresa; operador e técnico não veem o que
 * foi ocultado para o seu papel (falha na tabela de ocultação = nada oculto).
 */
internal fun ApplicationCall.canSeeProcess(manager: ManagerRecord, process: ProcessRecord): Boolean {
    val scope = companyScope(manager)
    if (!scope.sees(process.businessId)) return false
    if (manager.isTechnician() && !scheduleLookup.hasScheduleForProcess(process.id, scope.visibleBusinesses)) return false
    if (manager.role in ManagerRecord.HIDEABLE_ROLES && visibilityRepository.isHiddenSafe("process", process.id, manager.role)) return false
    return true
}

/**
 * Authenticates, resolves the Manager, and checks the process is visible to
 * them — the same four-step preamble every process-scoped route needs.
 * With [edit], also requires a role that may change it (403 otherwise).
 * Responds with the right status itself and returns null when it fails.
 */
private suspend fun ApplicationCall.requireProcessAccess(
    authClient: SupabaseAuthClient,
    managerRepository: ManagerRepository,
    processRepository: ProcessRepository,
    processId: UUID,
    edit: Boolean = false,
): ProcessAccess? {
    val supabaseUser = requireSupabaseUser(authClient) ?: return null
    val manager = managerRepository.findOrCreateForSupabaseUser(
        supabaseUser.id,
        supabaseUser.email,
        supabaseUser.fullNameHint,
    )
    if (edit && !manager.canEdit()) {
        respond(HttpStatusCode.Forbidden, ErrorResponse(EDIT_FORBIDDEN))
        return null
    }
    val process = processRepository.findById(processId)
        ?.takeIf { canSeeProcess(manager, it) }
    if (process == null) {
        respond(HttpStatusCode.NotFound, ErrorResponse("Processo não encontrado."))
        return null
    }
    return ProcessAccess(process, manager)
}

private suspend fun ApplicationCall.requireProcess(
    authClient: SupabaseAuthClient,
    managerRepository: ManagerRepository,
    processRepository: ProcessRepository,
    processId: UUID,
    edit: Boolean = false,
): ProcessRecord? = requireProcessAccess(authClient, managerRepository, processRepository, processId, edit)?.process

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

private val SupabaseUserKey = AttributeKey<SupabaseUser>("SupabaseUser")

private suspend fun ApplicationCall.requireSupabaseUser(authClient: SupabaseAuthClient): SupabaseUser? {
    // Já verificado nesta requisição (ex.: pelo FeatureGate): não pergunta ao Supabase de novo.
    attributes.getOrNull(SupabaseUserKey)?.let { return it }
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
    attributes.put(SupabaseUserKey, user)
    return user
}

/** Cabeçalho com a empresa escolhida no login demo (setas do card). */
const val COMPANY_HEADER = "X-Deviante-Company"

internal val businessRepository = BusinessRepository()

/**
 * Empresa em nome da qual a requisição age: contas demo escolhem no login
 * (cabeçalho); as demais agem pela empresa gravada no seu cadastro.
 */
internal fun ApplicationCall.effectiveCompany(manager: ManagerRecord): String {
    val chosen = request.headers[COMPANY_HEADER]
        ?.takeIf { ManagerRepository.isDemoAccount(manager.email) }
        ?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }
        ?.takeIf { businessRepository.idByName(it) != null }
    return chosen
        ?: manager.businessId?.let { businessRepository.namesById()[it] }
        ?: AppConfig.productLine.variantOrDefault(null).client
}

/** Variante do produto (plano + exclusivas do application.yaml) da empresa da requisição. */
internal fun ApplicationCall.effectiveVariant(manager: ManagerRecord): ClientVariant =
    AppConfig.productLine.variantOrDefault(effectiveCompany(manager))

/**
 * Dados por empresa: cada registro guarda no banco a empresa dona
 * (business_id). O usuário enxerga só os da empresa em que entrou;
 * administrador, proprietário e mentor enxergam todos.
 */
class CompanyScope(val variant: ClientVariant, val businessId: UUID, private val seesAll: Boolean) {
    val visibleBusinesses: Set<UUID>? get() = if (seesAll) null else setOf(businessId)
    fun sees(recordBusinessId: UUID?): Boolean = seesAll || recordBusinessId == businessId
}

private val SEES_ALL_ROLES = setOf("admin", "owner", "mentor")

internal fun ApplicationCall.companyScope(manager: ManagerRecord): CompanyScope {
    val company = effectiveCompany(manager)
    val businessId = businessRepository.idByName(company)
        ?: manager.businessId
        ?: error("Empresa $company não cadastrada em deviante.businesses")
    return CompanyScope(effectiveVariant(manager), businessId, manager.role in SEES_ALL_ROLES)
}

class FeatureGateConfig {
    lateinit var feature: Feature
    lateinit var authClient: SupabaseAuthClient
    lateinit var managerRepository: ManagerRepository
}

/**
 * Ponto de variação na API: instalado numa rota, só deixa passar o cliente
 * cuja variante do produto inclui a funcionalidade; os outros recebem 403.
 */
val FeatureGate = createRouteScopedPlugin("FeatureGate", ::FeatureGateConfig) {
    val feature = pluginConfig.feature
    val authClient = pluginConfig.authClient
    val managerRepository = pluginConfig.managerRepository
    onCall { call ->
        val user = call.requireSupabaseUser(authClient) ?: return@onCall
        val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
        val variant = call.effectiveVariant(manager)
        if (!variant.has(feature)) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse("Este recurso não faz parte do plano ${variant.plan.label} de ${variant.client}."),
            )
        }
    }
}

private fun Route.requireFeature(feature: Feature, authClient: SupabaseAuthClient, managerRepository: ManagerRepository) {
    install(FeatureGate) {
        this.feature = feature
        this.authClient = authClient
        this.managerRepository = managerRepository
    }
}

/**
 * Exclusões (processo, atividade, equipamento, agenda) são permitidas só ao
 * administrador e ao gestor; operador, técnico e mentor recebem 403.
 */
private suspend fun ApplicationCall.requireDeleter(
    authClient: SupabaseAuthClient,
    managerRepository: ManagerRepository,
): ManagerRecord? {
    val user = requireSupabaseUser(authClient) ?: return null
    val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
    if (!manager.canDelete()) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("Somente o administrador ou o gestor pode excluir."))
        return null
    }
    return manager
}

const val EDIT_FORBIDDEN = "Seu papel não permite esta ação."

/**
 * Criar e alterar (processos, máquinas, agendas, monitoramentos) é do
 * administrador e do gestor; operador, técnico e mentor recebem 403.
 */
private suspend fun ApplicationCall.requireEditor(
    authClient: SupabaseAuthClient,
    managerRepository: ManagerRepository,
): ManagerRecord? {
    val user = requireSupabaseUser(authClient) ?: return null
    val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
    if (!manager.canEdit()) {
        respond(HttpStatusCode.Forbidden, ErrorResponse(EDIT_FORBIDDEN))
        return null
    }
    return manager
}

/** Tabelas novas (trace_notes, object_visibility) podem ainda não existir no banco. */
private suspend fun ApplicationCall.respondTableMissing(e: Exception) {
    application.environment.log.warn("Tabela de papéis indisponível: {}", e.message)
    respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("Tabela ainda não criada."))
}

/**
 * O catálogo de atividades e as operações são do administrador da empresa;
 * gestor, operador, técnico e mentor recebem 403.
 */
private suspend fun ApplicationCall.requireCatalogKeeper(
    authClient: SupabaseAuthClient,
    managerRepository: ManagerRepository,
): ManagerRecord? {
    val user = requireSupabaseUser(authClient) ?: return null
    val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
    if (!manager.keepsCatalog()) {
        respond(HttpStatusCode.Forbidden, ErrorResponse("Somente o administrador mantém o catálogo de atividades."))
        return null
    }
    return manager
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
    val traceNotesRepository = TraceNotesRepository()
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
                    call.respond(manager.toResponse(call.effectiveVariant(manager)))
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
                    call.respond(updated.toResponse(call.effectiveVariant(updated)))
                }
            }

            route("/processes") {
                get {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@get
                    val manager = managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    // O técnico chega aos processos só pela agenda de manutenção.
                    if (manager.isTechnician()) {
                        call.respond(emptyList<com.deviante.dto.ProcessResponse>())
                        return@get
                    }
                    val scope = call.companyScope(manager)
                    val hidden = if (manager.role in ManagerRecord.HIDEABLE_ROLES) {
                        visibilityRepository.hiddenIdsSafe("process", manager.role)
                    } else {
                        emptySet()
                    }
                    val labels = businessRepository.namesById()
                    val processes = processRepository.listAll()
                        .filter { scope.sees(it.businessId) && it.id !in hidden }
                        .map { it.toResponse(company = labels[it.businessId]) }
                    call.respond(processes)
                }

                post {
                    val manager = call.requireEditor(authClient, managerRepository) ?: return@post
                    val process = processRepository.create(manager.id, call.companyScope(manager).businessId)
                    call.respond(HttpStatusCode.Created, process.toResponse())
                }

                get("/{id}") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@get
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@get
                    }
                    val manager = managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val process = processRepository.findById(id)
                        ?.takeIf { call.canSeeProcess(manager, it) }
                    if (process == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Processo não encontrado."))
                        return@get
                    }
                    call.respond(process.toResponse())
                }

                put("/{id}") {
                    val manager = call.requireEditor(authClient, managerRepository) ?: return@put
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
                    if (fieldErrors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Corrija os campos destacados.", fieldErrors))
                        return@put
                    }

                    val visible = processRepository.findById(id)?.let { call.companyScope(manager).sees(it.businessId) } ?: false
                    if (!visible) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Processo não encontrado."))
                        return@put
                    }
                    val updated = processRepository.update(
                        id = id,
                        name = body.name.trim(),
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
                    if (call.requireProcess(authClient, managerRepository, processRepository, id, edit = true) == null) {
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
                    val deleter = call.requireDeleter(authClient, managerRepository) ?: return@delete
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@delete
                    }

                    val process = processRepository.findById(id)
                        ?.takeIf { call.companyScope(deleter).sees(it.businessId) }
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
                    val manager = managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    // Catálogo da empresa escolhida, também para o administrador.
                    val activities = activitiesRepository.listAll(call.companyScope(manager).businessId).map { it.toResponse() }
                    call.respond(activities)
                }

                post {
                    val manager = call.requireCatalogKeeper(authClient, managerRepository) ?: return@post
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
                        businessId = call.companyScope(manager).businessId,
                    )
                    call.respond(HttpStatusCode.Created, activity.toResponse())
                }

                get("/{id}") {
                    val supabaseUser = call.requireSupabaseUser(authClient) ?: return@get
                    val manager = managerRepository.findOrCreateForSupabaseUser(
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
                        ?.takeIf { it.businessId == call.companyScope(manager).businessId }
                    if (activity == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Atividade não encontrada."))
                        return@get
                    }
                    call.respond(activity.toResponse())
                }

                put("/{id}") {
                    val manager = call.requireCatalogKeeper(authClient, managerRepository) ?: return@put
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                        ?.takeIf { activitiesRepository.findById(it)?.businessId == call.companyScope(manager).businessId }
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

                delete("/{id}") {
                    val manager = call.requireCatalogKeeper(authClient, managerRepository) ?: return@delete
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                        ?.takeIf { activitiesRepository.findById(it)?.businessId == call.companyScope(manager).businessId }
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de atividade inválido."))
                        return@delete
                    }
                    when (activitiesRepository.delete(id)) {
                        ActivityDeletion.DELETED -> call.respond(HttpStatusCode.NoContent)
                        ActivityDeletion.NOT_FOUND ->
                            call.respond(HttpStatusCode.NotFound, ErrorResponse("Atividade não encontrada."))
                        ActivityDeletion.IN_USE -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("A atividade está mapeada em operações de um log e não pode ser excluída."),
                        )
                    }
                }
            }

            get("/operations") {
                val manager = call.requireCatalogKeeper(authClient, managerRepository) ?: return@get
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
                requireFeature(Feature.EQUIPMENT, authClient, managerRepository)
                get {
                    val user = call.requireSupabaseUser(authClient) ?: return@get
                    val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
                    val hidden = if (manager.role in ManagerRecord.HIDEABLE_ROLES) {
                        visibilityRepository.hiddenIdsSafe("equipment", manager.role).map { it.toString() }.toSet()
                    } else {
                        emptySet()
                    }
                    call.respond(
                        maintenanceRepository.listEquipment(call.companyScope(manager).visibleBusinesses)
                            .filter { it.id !in hidden },
                    )
                }
                post {
                    val manager = call.requireEditor(authClient, managerRepository) ?: return@post
                    val body = call.receive<EquipmentRequest>()
                    if (body.name.isBlank()) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("Nome do equipamento é obrigatório.")); return@post }
                    call.respond(HttpStatusCode.Created, maintenanceRepository.createEquipment(manager.id, call.companyScope(manager).businessId, body))
                }
                get("/{id}") {
                    val user = call.requireSupabaseUser(authClient) ?: return@get
                    val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                        ?.takeUnless {
                            manager.role in ManagerRecord.HIDEABLE_ROLES &&
                                visibilityRepository.isHiddenSafe("equipment", it, manager.role)
                        }
                    val item = id?.let(maintenanceRepository::findEquipment)
                    if (item == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Equipamento não encontrado.")) else call.respond(item)
                }
                put("/{id}") {
                    call.requireEditor(authClient, managerRepository) ?: return@put
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    val body = call.receive<EquipmentRequest>()
                    if (id == null || body.name.isBlank()) { call.respond(HttpStatusCode.BadRequest, ErrorResponse("Equipamento ou nome inválido.")); return@put }
                    val item = maintenanceRepository.updateEquipment(id, body)
                    if (item == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Equipamento não encontrado.")) else call.respond(item)
                }
                delete("/{id}") {
                    call.requireDeleter(authClient, managerRepository) ?: return@delete
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
                    call.requireEditor(authClient, managerRepository) ?: return@post
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
                    call.requireEditor(authClient, managerRepository) ?: return@delete
                    val equipmentId = call.parameters["id"]?.let(::runCatchingUuid)
                    val parameterId = call.parameters["parameterId"]?.let(::runCatchingUuid)
                    if (equipmentId == null || parameterId == null || !maintenanceRepository.deleteParameter(equipmentId, parameterId)) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Parâmetro não encontrado."))
                    } else call.respond(HttpStatusCode.NoContent)
                }
                post("/{id}/readings") {
                    call.requireEditor(authClient, managerRepository) ?: return@post
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
                    val manager = call.requireEditor(authClient, managerRepository) ?: return@post
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
                        equipmentId, series.monitoringId, parameterId, null, detection.method, detection.delta, detection.treatment,
                        detection.observationCount, hash, routeJson.encodeToString(detection), routeJson.encodeToString(provenance),
                        prediction?.rulTraces, "traces", prediction?.failureProbability,
                        prediction?.failureHorizonTraces?.toDouble(), prediction?.let { "traces" },
                        prediction?.provenance?.modelVersion, recommendation,
                    )
                    if (prediction != null && recommendation != null) {
                        maintenanceRepository.createRecommendation(manager.id, equipmentId, stored.id, RecommendationRequest(recommendation))
                    }
                    call.respond(HttpStatusCode.Created, EquipmentAnalysisResponse(
                        stored.id.toString(), equipmentId.toString(), series.monitoringId.toString(), parameterId.toString(), null, hash,
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
                    if (processId == null || equipmentId == null || call.requireProcess(authClient, managerRepository, processRepository, processId, edit = true) == null) return@put
                    if (!maintenanceRepository.linkProcessEquipment(processId, equipmentId)) call.respond(HttpStatusCode.NotFound, ErrorResponse("Equipamento não encontrado."))
                    else call.respond(HttpStatusCode.NoContent)
                }
                delete("/{equipmentId}") {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    val equipmentId = call.parameters["equipmentId"]?.let(::runCatchingUuid)
                    if (processId == null || equipmentId == null || call.requireProcess(authClient, managerRepository, processRepository, processId, edit = true) == null) return@delete
                    if (!maintenanceRepository.unlinkProcessEquipment(processId, equipmentId)) call.respond(HttpStatusCode.NotFound, ErrorResponse("Associação não encontrada."))
                    else call.respond(HttpStatusCode.NoContent)
                }
            }

            route("/monitorings") {
                get { val u = call.requireSupabaseUser(authClient) ?: return@get; val m = managerRepository.findOrCreateForSupabaseUser(u.id,u.email,u.fullNameHint); call.respond(maintenanceRepository.listMonitorings(call.companyScope(m).visibleBusinesses)) }
                post {
                    val m = call.requireEditor(authClient, managerRepository) ?: return@post
                    val body = call.receive<CreateMonitoringRequest>().toMonitoringRequest()
                    call.respond(HttpStatusCode.Created, maintenanceRepository.createMonitoring(m.id, call.companyScope(m).businessId, body))
                }
                get("/{id}") { call.requireSupabaseUser(authClient) ?: return@get; val item = call.parameters["id"]?.let(::runCatchingUuid)?.let(maintenanceRepository::findMonitoring); if(item==null) call.respond(HttpStatusCode.NotFound,ErrorResponse("Monitoramento não encontrado.")) else call.respond(item) }
                put("/{id}") { call.requireEditor(authClient, managerRepository) ?: return@put; val id=call.parameters["id"]?.let(::runCatchingUuid); val body=call.receive<MonitoringRequest>(); val item=id?.let{maintenanceRepository.updateMonitoring(it,body)}; if(item==null) call.respond(HttpStatusCode.NotFound,ErrorResponse("Monitoramento não encontrado.")) else call.respond(item) }
                delete("/{id}") { call.requireEditor(authClient, managerRepository) ?: return@delete; val id=call.parameters["id"]?.let(::runCatchingUuid); if(id==null||!maintenanceRepository.deleteMonitoring(id)) call.respond(HttpStatusCode.NotFound,ErrorResponse("Monitoramento não encontrado.")) else call.respond(HttpStatusCode.NoContent) }
                get("/{id}/equipment") { call.requireSupabaseUser(authClient) ?: return@get; val id=call.parameters["id"]?.let(::runCatchingUuid); if(id==null) call.respond(HttpStatusCode.BadRequest,ErrorResponse("ID inválido.")) else call.respond(maintenanceRepository.listMonitoringEquipment(id)) }
                post("/{id}/equipment") {
                    val m=call.requireEditor(authClient, managerRepository)?:return@post
                    val id=call.parameters["id"]?.let(::runCatchingUuid)
                    val body=call.receive<EquipmentRequest>()
                    if(id==null||body.name.isBlank()) { call.respond(HttpStatusCode.BadRequest,ErrorResponse("Monitoramento e nome do equipamento são obrigatórios.")); return@post }
                    if(maintenanceRepository.findMonitoring(id)==null) { call.respond(HttpStatusCode.NotFound,ErrorResponse("Monitoramento não encontrado.")); return@post }
                    val equipment=maintenanceRepository.createEquipment(m.id,call.companyScope(m).businessId,body)
                    maintenanceRepository.linkMonitoringEquipment(id,java.util.UUID.fromString(equipment.id))
                    call.respond(HttpStatusCode.Created,equipment)
                }
                put("/{id}/equipment/{equipmentId}") { call.requireEditor(authClient, managerRepository) ?: return@put; val id=call.parameters["id"]?.let(::runCatchingUuid); val eid=call.parameters["equipmentId"]?.let(::runCatchingUuid); if(id==null||eid==null||!maintenanceRepository.linkMonitoringEquipment(id,eid)) call.respond(HttpStatusCode.NotFound,ErrorResponse("Monitoramento ou equipamento não encontrado.")) else call.respond(HttpStatusCode.NoContent) }
                delete("/{id}/equipment/{equipmentId}") { call.requireEditor(authClient, managerRepository) ?: return@delete; val id=call.parameters["id"]?.let(::runCatchingUuid); val eid=call.parameters["equipmentId"]?.let(::runCatchingUuid); if(id==null||eid==null||!maintenanceRepository.unlinkMonitoringEquipment(id,eid)) call.respond(HttpStatusCode.NotFound,ErrorResponse("Associação não encontrada.")) else call.respond(HttpStatusCode.NoContent) }
                get("/{id}/parameters") { call.requireSupabaseUser(authClient) ?: return@get; val id=call.parameters["id"]?.let(::runCatchingUuid); if(id==null) call.respond(HttpStatusCode.BadRequest,ErrorResponse("ID inválido.")) else call.respond(maintenanceRepository.listParameters(id)) }
                post("/{id}/parameters") {
                    call.requireEditor(authClient, managerRepository) ?: return@post; val id=call.parameters["id"]?.let(::runCatchingUuid); val body=call.receive<MonitoringParameterRequest>()
                    if(id==null||body.name.isBlank()||runCatchingUuid(body.equipmentId)==null) { call.respond(HttpStatusCode.BadRequest,ErrorResponse("Monitoramento, equipamento ou parâmetro inválido.")); return@post }
                    val item=maintenanceRepository.createParameter(id,body); if(item==null) call.respond(HttpStatusCode.UnprocessableEntity,ErrorResponse("Associe o equipamento ao monitoramento antes de criar o parâmetro.")) else call.respond(HttpStatusCode.Created,item)
                }
            }

            route("/monitoring-parameters/{id}/readings") {
                get { call.requireSupabaseUser(authClient) ?: return@get; val id=call.parameters["id"]?.let(::runCatchingUuid); if(id==null) call.respond(HttpStatusCode.BadRequest,ErrorResponse("ID inválido.")) else call.respond(maintenanceRepository.listReadings(id)) }
                post {
                    call.requireEditor(authClient, managerRepository) ?: return@post; val id=call.parameters["id"]?.let(::runCatchingUuid); val body=call.receive<AppendReadingsRequest>()
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
                    call.respond(EquipmentAnalysisResponse(stored.id.toString(),stored.equipmentId.toString(),stored.monitoringId.toString(),stored.parameterId.toString(),stored.monitoringAnalysisId?.toString(),stored.inputSha256,routeJson.decodeFromString(stored.resultJson),routeJson.decodeFromString(stored.provenanceJson),stored.rulValue,stored.rulUnit,stored.failureProbability,stored.failureHorizonValue,stored.failureHorizonUnit,stored.modelVersion,stored.recommendation,stored.createdAt.toString()))
                }
                post("/recommendations") {
                    val m=call.requireEditor(authClient, managerRepository)?:return@post; val id=call.parameters["id"]?.let(::runCatchingUuid); val stored=id?.let(maintenanceRepository::findAnalysis); val body=call.receive<RecommendationRequest>()
                    if(stored==null||body.action.isBlank()) { call.respond(HttpStatusCode.BadRequest,ErrorResponse("Análise e ação são obrigatórias.")); return@post }
                    call.respond(HttpStatusCode.Created,maintenanceRepository.createRecommendation(m.id,stored.equipmentId,stored.id,body)!!)
                }
            }

            route("/schedules") {
                requireFeature(Feature.SCHEDULES, authClient, managerRepository)
                // Função exclusiva de um cliente: só existe na variante que a contratou.
                route("/export.csv") {
                    requireFeature(Feature.SCHEDULE_EXPORT, authClient, managerRepository)
                    get {
                        val u = call.requireSupabaseUser(authClient) ?: return@get
                        val m = managerRepository.findOrCreateForSupabaseUser(u.id, u.email, u.fullNameHint)
                        val rows = maintenanceRepository.listSchedules(null, call.companyScope(m).visibleBusinesses)
                        call.respondText(schedulesToCsv(rows), ContentType.Text.CSV.withCharset(Charsets.UTF_8))
                    }
                }
                get {
                    val u=call.requireSupabaseUser(authClient)?:return@get
                    val m = managerRepository.findOrCreateForSupabaseUser(u.id,u.email,u.fullNameHint)
                    val equipmentId = call.request.queryParameters["equipmentId"]?.let(::runCatchingUuid)
                    call.respond(maintenanceRepository.listSchedules(equipmentId, call.companyScope(m).visibleBusinesses))
                }
                post {
                    val m=call.requireEditor(authClient, managerRepository)?:return@post; val body=call.receive<MaintenanceScheduleRequest>()
                    val start=runCatching{OffsetDateTime.parse(body.scheduledStart)}.getOrNull(); val end=body.scheduledEnd?.let{runCatching{OffsetDateTime.parse(it)}.getOrNull()}
                    if(body.title.isBlank()||start==null||(body.scheduledEnd!=null&&end==null)||(end!=null&&end<start)) { call.respond(HttpStatusCode.BadRequest,ErrorResponse("Agenda de manutenção inválida.")); return@post }
                    val item=runCatching{maintenanceRepository.createSchedule(m.id,body)}.getOrNull(); if(item==null) call.respond(HttpStatusCode.UnprocessableEntity,ErrorResponse("Referências da agenda inválidas.")) else call.respond(HttpStatusCode.Created,item)
                }
                put("/{id}") {
                    call.requireEditor(authClient, managerRepository) ?: return@put
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    val body = call.receive<MaintenanceScheduleRequest>()
                    val start = runCatching { OffsetDateTime.parse(body.scheduledStart) }.getOrNull()
                    val end = body.scheduledEnd?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
                    if (id == null || body.title.isBlank() || start == null || (body.scheduledEnd != null && end == null) || (end != null && end < start)) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Agenda de manutenção inválida."))
                        return@put
                    }
                    val item = runCatching { maintenanceRepository.updateSchedule(id, body) }.getOrNull()
                    if (item == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Agenda não encontrada.")) else call.respond(item)
                }
                // O técnico registra o andamento da manutenção; quem edita também.
                patch("/{id}/status") {
                    val u = call.requireSupabaseUser(authClient) ?: return@patch
                    val m = managerRepository.findOrCreateForSupabaseUser(u.id, u.email, u.fullNameHint)
                    if (!m.canEdit() && !m.isTechnician()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse(EDIT_FORBIDDEN))
                        return@patch
                    }
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    val owner = id?.let(maintenanceRepository::scheduleBusiness)
                    if (id == null || owner == null || !call.companyScope(m).sees(owner.businessId)) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Agenda não encontrada."))
                        return@patch
                    }
                    val body = call.receive<ScheduleStatusRequest>()
                    if (body.status !in SCHEDULE_STATUSES) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Situação inválida: use ${SCHEDULE_STATUSES.joinToString(", ")}."),
                        )
                        return@patch
                    }
                    val item = maintenanceRepository.updateScheduleStatus(id, body.status, body.notes)
                    if (item == null) call.respond(HttpStatusCode.NotFound, ErrorResponse("Agenda não encontrada.")) else call.respond(item)
                }
                delete("/{id}") { call.requireDeleter(authClient, managerRepository)?:return@delete; val id=call.parameters["id"]?.let(::runCatchingUuid); if(id==null||!maintenanceRepository.deleteSchedule(id)) call.respond(HttpStatusCode.NotFound,ErrorResponse("Agenda não encontrada.")) else call.respond(HttpStatusCode.NoContent) }
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
                    val process = call.requireProcess(authClient, managerRepository, processRepository, processId, edit = true)
                        ?: return@put

                    // Só vincula atividade do catálogo da empresa dona do processo.
                    val activity = activitiesRepository.findById(activityId)
                        ?.takeIf { it.businessId == process.businessId }
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
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId, edit = true) == null) {
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
                    val process = call.requireProcess(authClient, managerRepository, processRepository, processId)
                        ?: return@get

                    val activityNames = activitiesRepository.listAll(process.businessId).associate { it.id to it.name }
                    val operations = eventLogsRepository.findByProcessId(processId)
                        .flatMap { operationsRepository.listForEventLog(it.id) }
                        .map { it.toResponse(activityNames[it.activityId]) }

                    call.respond(operations)
                }
            }

            /**
             * Operations CRUD inside a process, for every role that sees it.
             * Map (create/update) only to an activity of the process's own
             * company catalog; unmap (delete) clears the link. The catalog
             * itself stays with the administrator.
             */
            route("/processes/{processId}/operations/{id}") {
                suspend fun ApplicationCall.processOperation(): Pair<ProcessRecord, UUID>? {
                    // Mapear e desmapear altera o processo: só quem edita.
                    val processId = parameters["processId"]?.let(::runCatchingUuid)
                    val id = parameters["id"]?.let(::runCatchingUuid)
                    if (processId == null || id == null) {
                        respond(HttpStatusCode.BadRequest, ErrorResponse("ID inválido."))
                        return null
                    }
                    val process = requireProcess(authClient, managerRepository, processRepository, processId, edit = true) ?: return null
                    val logIds = eventLogsRepository.findByProcessId(processId).map { it.id }.toSet()
                    if (operationsRepository.findById(id)?.eventLogId !in logIds) {
                        respond(HttpStatusCode.NotFound, ErrorResponse("Operação não encontrada."))
                        return null
                    }
                    return process to id
                }

                put("/activity") {
                    val (process, id) = call.processOperation() ?: return@put
                    val body = call.receive<MapOperationRequest>()
                    val activity = runCatchingUuid(body.activityId)?.let(activitiesRepository::findById)
                        ?.takeIf { it.businessId == process.businessId }
                    if (activity == null) {
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse("Atividade fora do catálogo da empresa."))
                        return@put
                    }
                    val updated = operationsRepository.mapToActivity(id, activity.id)!!
                    call.respond(updated.toResponse(activity.name))
                }

                delete("/activity") {
                    val (_, id) = call.processOperation() ?: return@delete
                    call.respond(operationsRepository.unmap(id)!!.toResponse())
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

                    val eventLogIds = call.request.queryParameters.getAll("eventLogId").orEmpty()
                        .mapNotNull(::runCatchingUuid)
                        .toSet()
                    if (eventLogIds.size != call.request.queryParameters.getAll("eventLogId").orEmpty().size) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de log de eventos inválido."))
                        return@get
                    }
                    if (eventLogIds.isNotEmpty()) {
                        val available = eventLogsRepository.findByProcessId(processId)
                            .filter { it.parseStatus == "parsed" }
                            .map { it.id }
                            .toSet()
                        if (!available.containsAll(eventLogIds)) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Selecione apenas logs parseados deste processo."))
                            return@get
                        }
                    }
                    call.respond(graphRepository.graph(processId, eventLogIds))
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

                    val eventLogIds = call.request.queryParameters.getAll("eventLogId").orEmpty()
                        .mapNotNull(::runCatchingUuid)
                        .toSet()
                    if (eventLogIds.size != call.request.queryParameters.getAll("eventLogId").orEmpty().size) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de log de eventos inválido."))
                        return@get
                    }
                    if (eventLogIds.isNotEmpty()) {
                        val available = eventLogsRepository.findByProcessId(processId)
                            .filter { it.parseStatus == "parsed" }
                            .map { it.id }
                            .toSet()
                        if (!available.containsAll(eventLogIds)) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Selecione apenas logs parseados deste processo."))
                            return@get
                        }
                    }
                    call.respond(graphRepository.variants(processId, eventLogIds))
                }
            }

            route("/processes/{processId}/analysis") {
                post {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@post
                    }
                    val process = call.requireProcess(authClient, managerRepository, processRepository, processId, edit = true)
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

                    val requestedEventLogIds = try {
                        body.eventLogIds.map { UUID.fromString(it) }.toSet()
                    } catch (err: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de log de eventos inválido."))
                        return@post
                    }
                    if (requestedEventLogIds.isNotEmpty()) {
                        val available = eventLogsRepository.findByProcessId(processId)
                            .filter { it.parseStatus == "parsed" }
                            .map { it.id }
                            .toSet()
                        if (!available.containsAll(requestedEventLogIds)) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Selecione apenas logs parseados deste processo."))
                            return@post
                        }
                    }

                    val series = try {
                        analysisRepository.latestSeries(processId, requestedEventLogIds, excludedActivityIds, excludedTraceIds)
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

                    val linkedMachines = maintenanceRepository.listProcessEquipment(processId)
                    val response = ProcessAnalysisResponse(
                        eventLog = series.eventLogs.first().toResponse(),
                        eventLogs = series.eventLogs.map { it.toResponse() },
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
                        linkedMachineIds = linkedMachines.map { it.id },
                        linkedMachineNames = linkedMachines.map { it.name },
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
                    val manager = managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val scope = call.companyScope(manager)
                    call.respond(analysisRepository.listSummaries(scope.visibleBusinesses).map { it.toSummaryResponse() })
                }

                post {
                    call.requireEditor(authClient, managerRepository) ?: return@post
                    val body = call.receive<CreateAnalysisRequest>()
                    val processId = runCatchingUuid(body.processId)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@post
                    }
                    val process = call.requireProcess(authClient, managerRepository, processRepository, processId, edit = true)
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

                delete("/{id}") {
                    call.requireDeleter(authClient, managerRepository) ?: return@delete
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de análise inválido."))
                        return@delete
                    }
                    if (!analysisRepository.delete(id)) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Análise não encontrada."))
                        return@delete
                    }
                    call.respond(HttpStatusCode.NoContent)
                }

                // Persist the Manager's subtractive filter so reopening an
                // analysis continues where they left off, without recomputing.
                put("/{id}/filter") {
                    call.requireEditor(authClient, managerRepository) ?: return@put
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
                    if (call.requireProcess(authClient, managerRepository, processRepository, processId, edit = true) == null) {
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
                    val access = call.requireProcessAccess(authClient, managerRepository, processRepository, processId, edit = true)
                        ?: return@post
                    val process = access.process
                    val manager = access.manager

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

                    // O gestor só mapeia para atividades que já existem no catálogo;
                    // criar atividade nova é do administrador.
                    val result = try {
                        mappingRepository.resolveAll(processId, resolutions, process.businessId, manager.keepsCatalog())
                    } catch (e: MappingRepository.UnknownActivitiesException) {
                        call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(e.message ?: "Atividade fora do catálogo."))
                        return@post
                    }
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

            /** Anotações sobre traces do processo: leem todos que veem o processo. */
            route("/processes/{processId}/trace-notes") {
                get {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@get
                    }
                    call.requireProcess(authClient, managerRepository, processRepository, processId) ?: return@get
                    val notes = try {
                        traceNotesRepository.list(processId)
                    } catch (e: Exception) {
                        call.respondTableMissing(e)
                        return@get
                    }
                    call.respond(notes)
                }

                post {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    if (processId == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de processo inválido."))
                        return@post
                    }
                    val user = call.requireSupabaseUser(authClient) ?: return@post
                    val author = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
                    if (!author.writesTraceNotes()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse(EDIT_FORBIDDEN))
                        return@post
                    }
                    val access = call.requireProcessAccess(authClient, managerRepository, processRepository, processId)
                        ?: return@post
                    val body = call.receive<CreateTraceNoteRequest>()
                    val error = traceNoteError(body.note, body.activityName)
                    if (error != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error, mapOf("note" to error)))
                        return@post
                    }
                    val traceId = runCatchingUuid(body.traceId)
                    val caseId = traceId?.let { traceNotesRepository.caseIdForProcessTrace(processId, it) }
                    if (traceId == null || caseId == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Trace não encontrado neste processo."))
                        return@post
                    }
                    val created = try {
                        val id = traceNotesRepository.create(
                            processId = processId,
                            traceId = traceId,
                            activityName = body.activityName.trim(),
                            note = body.note.trim(),
                            managerId = access.manager.id,
                            businessId = access.process.businessId,
                        )
                        traceNotesRepository.list(processId).first { it.id == id.toString() }
                    } catch (e: Exception) {
                        call.respondTableMissing(e)
                        return@post
                    }
                    call.respond(HttpStatusCode.Created, created)
                }

                delete("/{id}") {
                    val processId = call.parameters["processId"]?.let(::runCatchingUuid)
                    val id = call.parameters["id"]?.let(::runCatchingUuid)
                    if (processId == null || id == null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID inválido."))
                        return@delete
                    }
                    val access = call.requireProcessAccess(authClient, managerRepository, processRepository, processId)
                        ?: return@delete
                    val note = try {
                        traceNotesRepository.find(processId, id)
                    } catch (e: Exception) {
                        call.respondTableMissing(e)
                        return@delete
                    }
                    if (note == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Anotação não encontrada."))
                        return@delete
                    }
                    if (note.managerId != access.manager.id && !access.manager.canEdit()) {
                        call.respond(HttpStatusCode.Forbidden, ErrorResponse(EDIT_FORBIDDEN))
                        return@delete
                    }
                    try {
                        traceNotesRepository.delete(id)
                    } catch (e: Exception) {
                        call.respondTableMissing(e)
                        return@delete
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            /** Processo ou máquina oculto para operador e/ou técnico. */
            route("/visibility/{objectType}/{objectId}") {
                // Empresa dona do objeto, se ele existe e está no escopo do usuário.
                fun ApplicationCall.objectBusiness(manager: ManagerRecord, objectType: String, objectId: UUID): UUID? {
                    val businessId = when (objectType) {
                        "process" -> processRepository.findById(objectId)?.businessId
                        "equipment" -> maintenanceRepository.equipmentBusinessId(objectId)
                        else -> null
                    }
                    return businessId?.takeIf { companyScope(manager).sees(it) }
                }

                suspend fun ApplicationCall.visibilityTarget(manager: ManagerRecord): Triple<String, UUID, UUID>? {
                    val objectType = parameters["objectType"].orEmpty()
                    val objectId = parameters["objectId"]?.let(::runCatchingUuid)
                    if (objectType !in VISIBILITY_OBJECT_TYPES || objectId == null) {
                        respond(HttpStatusCode.BadRequest, ErrorResponse("Use process ou equipment e um ID válido."))
                        return null
                    }
                    val businessId = objectBusiness(manager, objectType, objectId)
                    if (businessId == null) {
                        respond(HttpStatusCode.NotFound, ErrorResponse("Objeto não encontrado."))
                        return null
                    }
                    return Triple(objectType, objectId, businessId)
                }

                get {
                    val user = call.requireSupabaseUser(authClient) ?: return@get
                    val manager = managerRepository.findOrCreateForSupabaseUser(user.id, user.email, user.fullNameHint)
                    val (objectType, objectId, _) = call.visibilityTarget(manager) ?: return@get
                    val roles = try {
                        visibilityRepository.hiddenRoles(objectType, objectId)
                    } catch (e: Exception) {
                        call.respondTableMissing(e)
                        return@get
                    }
                    call.respond(VisibilityResponse(objectType, objectId.toString(), roles))
                }

                put {
                    val manager = call.requireEditor(authClient, managerRepository) ?: return@put
                    val (objectType, objectId, businessId) = call.visibilityTarget(manager) ?: return@put
                    val body = call.receive<VisibilityRequest>()
                    val invalid = body.hiddenRoles.filter { it !in ManagerRecord.HIDEABLE_ROLES }
                    if (invalid.isNotEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorResponse("Só é possível ocultar para operator ou technician."),
                        )
                        return@put
                    }
                    val roles = try {
                        visibilityRepository.replace(businessId, objectType, objectId, body.hiddenRoles.toSet(), manager.id)
                    } catch (e: Exception) {
                        call.respondTableMissing(e)
                        return@put
                    }
                    call.respond(VisibilityResponse(objectType, objectId.toString(), roles))
                }
            }

            route("/operations/{id}/map") {
                post {
                    call.requireCatalogKeeper(authClient, managerRepository) ?: return@post
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
                    call.requireCatalogKeeper(authClient, managerRepository) ?: return@post
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

/** CSV da agenda de manutenção (separador `;`, como o Excel em português espera). */
internal fun schedulesToCsv(rows: List<MaintenanceScheduleResponse>): String {
    fun cell(value: String?) = "\"" + (value ?: "").replace("\"", "\"\"") + "\""
    val header = listOf("titulo", "equipamento", "inicio", "fim", "situacao", "observacoes").joinToString(";")
    val lines = rows.map { row ->
        listOf(row.title, row.equipmentId, row.scheduledStart, row.scheduledEnd, row.status, row.notes).joinToString(";") { cell(it) }
    }
    return (listOf(header) + lines).joinToString("\n", postfix = "\n")
}

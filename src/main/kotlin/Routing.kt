package com.deviante

import com.deviante.dto.ErrorResponse
import com.deviante.dto.UpdateManagerRequest
import com.deviante.dto.UpdateProcessRequest
import com.deviante.dto.toResponse
import com.deviante.repository.ManagerRepository
import com.deviante.repository.ProcessRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

private val LANGUAGE_PATTERN = Regex("^[a-z]{2}(-[A-Z]{2})?$")

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
                    if (!LANGUAGE_PATTERN.matches(body.firstLanguage.trim())) {
                        fieldErrors["firstLanguage"] = "Idioma principal deve ser um código válido (ex.: pt, en)."
                    }
                    if (!LANGUAGE_PATTERN.matches(body.targetLanguage.trim())) {
                        fieldErrors["targetLanguage"] = "Idioma de interface deve ser um código válido (ex.: pt, en)."
                    }
                    if (body.firstLanguage.trim() == body.targetLanguage.trim()) {
                        fieldErrors["targetLanguage"] = "O idioma de interface deve ser diferente do idioma principal."
                    }
                    if (body.locationEnabled && body.basedIn.isNullOrBlank()) {
                        fieldErrors["basedIn"] = "Informe onde você está baseado."
                    }
                    if (fieldErrors.isNotEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("Corrija os campos destacados.", fieldErrors))
                        return@put
                    }

                    // Ensure the manager row exists (first update right after OAuth login).
                    managerRepository.findOrCreateForSupabaseUser(supabaseUser.id, supabaseUser.email, supabaseUser.fullNameHint)

                    val updated = managerRepository.update(
                        userId = supabaseUser.id,
                        fullName = body.fullName.trim(),
                        firstLanguage = body.firstLanguage.trim(),
                        targetLanguage = body.targetLanguage.trim(),
                        locationEnabled = body.locationEnabled,
                        basedIn = body.basedIn?.trim(),
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
                    val manager = managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val processes = processRepository.listForManager(manager.id).map { it.toResponse() }
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
                    val manager = managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val process = processRepository.findByIdForManager(id, manager.id)
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

                    val manager = managerRepository.findOrCreateForSupabaseUser(
                        supabaseUser.id,
                        supabaseUser.email,
                        supabaseUser.fullNameHint,
                    )
                    val updated = processRepository.update(
                        id = id,
                        managerId = manager.id,
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
                    val deleted = processRepository.delete(id, manager.id)
                    if (!deleted) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("Processo não encontrado."))
                        return@delete
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private fun runCatchingUuid(value: String): UUID? = try {
    UUID.fromString(value)
} catch (_: IllegalArgumentException) {
    null
}

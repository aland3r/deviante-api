package com.deviante

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

data class SupabaseUser(val id: UUID, val email: String, val fullNameHint: String)

@Serializable
private data class SupabaseUserResponse(
    val id: String,
    val email: String? = null,
    val user_metadata: SupabaseUserMetadata? = null,
)

@Serializable
private data class SupabaseUserMetadata(
    val full_name: String? = null,
    val name: String? = null,
)

class SupabaseAuthClient(private val baseUrl: String, private val anonKey: String) {
    private val logger = LoggerFactory.getLogger(SupabaseAuthClient::class.java)

    private val client = HttpClient(CIO) {
        // Supabase's /auth/v1/user payload carries far more fields than we model
        // (aud, role, app_metadata, identities, ...). Strict parsing throws on
        // those and made every valid token look expired.
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /** Verifies a Supabase access token by asking Supabase Auth who it belongs to. */
    suspend fun verify(accessToken: String): SupabaseUser? {
        if (baseUrl.isBlank() || anonKey.isBlank()) {
            logger.error("Supabase auth is not configured (SUPABASE_URL / SUPABASE_ANON_KEY missing).")
            return null
        }

        return try {
            val response = client.get("$baseUrl/auth/v1/user") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", anonKey)
            }
            if (!response.status.isSuccess()) {
                logger.warn("Supabase rejected the access token: {} {}", response.status, response.bodyAsText())
                return null
            }

            val body = response.body<SupabaseUserResponse>()
            SupabaseUser(
                id = UUID.fromString(body.id),
                email = body.email ?: "",
                fullNameHint = body.user_metadata?.full_name ?: body.user_metadata?.name ?: "",
            )
        } catch (error: Exception) {
            logger.error("Failed to verify Supabase access token.", error)
            null
        }
    }
}

fun Application.configureSupabaseAuthClient(): SupabaseAuthClient {
    val url = environment.config.propertyOrNull("supabase.url")?.getString().orEmpty()
    val anonKey = environment.config.propertyOrNull("supabase.anonKey")?.getString().orEmpty()
    return SupabaseAuthClient(url, anonKey)
}

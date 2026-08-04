package com.deviante

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Client for the Python/FastAPI process-mining service (`deviante/mining`).
 *
 * That service is stateless compute — PM4Py has no practical JVM equivalent
 * for XES parsing. It returns a parse result; **this API persists it**. See
 * gestalt-kit/docs/architecture.md § Process mining exception: Kotlin owns
 * persistence end to end and the browser never talks to the Python service.
 */
@Serializable
data class ParsedEventDto(
    val rawLabel: String,
    val occurrenceCount: Int,
    val caseCount: Int = 0,
    val totalDurationSeconds: Double = 0.0,
    val meanDurationSeconds: Double = 0.0,
)

@Serializable
data class ParsedTraceEventDto(
    val rawLabel: String,
    val sequenceIndex: Int,
    val occurredAt: String? = null,
    val durationSeconds: Double? = null,
)

@Serializable
data class ParsedTraceDto(
    val caseId: String,
    val eventCount: Int,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val durationSeconds: Double? = null,
    val events: List<ParsedTraceEventDto> = emptyList(),
)

@Serializable
data class ParsedLogDto(
    val fileName: String? = null,
    val format: String,
    val operationCount: Int,
    val traceCount: Int,
    val events: List<ParsedEventDto> = emptyList(),
    val traces: List<ParsedTraceDto> = emptyList(),
)

@Serializable
private data class MiningErrorDto(val detail: String? = null)

class MiningAnalysisException(message: String) : RuntimeException(message)

@Serializable
private data class DetectSeriesRequest(
    val values: List<Double>,
    val delta: Double,
    val treatment: String,
)

@Serializable
data class DetectedDriftDto(
    val index: Int,
    @SerialName("anomaly_start_index")
    val anomalyStartIndex: Int,
    val value: Double,
    val width: Double,
    val estimation: Double,
)

@Serializable
data class DetectSeriesResponse(
    val method: String,
    val delta: Double,
    val treatment: String = "treated",
    @SerialName("observation_count")
    val observationCount: Int,
    @SerialName("smoothing_window")
    val smoothingWindow: Int,
    @SerialName("processed_values")
    val processedValues: List<Double>,
    @SerialName("outlier_indices")
    val outlierIndices: List<Int>,
    val drifts: List<DetectedDriftDto>,
)

@Serializable
data class MaintenanceObservationRequest(
    val machineOperating: Double,
    val rawMaterialLoading: Double,
    val shortDowntime: Double,
    val driftDetected: Boolean = false,
)

@Serializable
private data class MaintenancePredictionRequest(
    val history: List<MaintenanceObservationRequest>,
)

@Serializable
data class MaintenancePredictionProvenance(
    val source: String,
    val modelVersion: String,
    val failureModel: String,
    val rulModel: String,
    val failureHorizonTraces: Int,
    val rulWindowTraces: Int,
    val trainingRunCount: Int,
    val trainingObservationCount: Int,
    val labelledFailureCount: Int,
    val excludedFeatures: List<String> = emptyList(),
    val trainingDatasets: List<String> = emptyList(),
)

@Serializable
data class MaintenancePredictionResponse(
    val failureProbability: Double,
    val failureHorizonTraces: Int,
    val rulTraces: Double,
    val rulUnit: String = "traces",
    val provenance: MaintenancePredictionProvenance,
)

/** The uploaded file was unreadable — the Manager can fix it and retry. */
class MiningParseException(message: String) : RuntimeException(message)

/** The service is down or unreachable — not the Manager's fault. */
class MiningUnavailableException(message: String) : RuntimeException(message)

class MiningClient(private val baseUrl: String) {
    private val logger = LoggerFactory.getLogger(MiningClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            // A 121-file XES corpus is large; PM4Py parsing dominates the
            // request, so the read timeout has to be generous while connect
            // stays short enough to fail fast when the service is not up.
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 300_000
            socketTimeoutMillis = 300_000
        }
    }

    suspend fun parse(fileName: String, content: ByteArray): ParsedLogDto {
        val response = try {
            client.post("$baseUrl/parse") {
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                content,
                                Headers.build {
                                    append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                },
                            )
                        },
                    ),
                )
            }
        } catch (err: Exception) {
            logger.error("[MiningClient] $baseUrl unreachable", err)
            throw MiningUnavailableException(
                "O serviço de análise de log não respondeu. Verifique se ele está no ar.",
            )
        }

        if (response.status == HttpStatusCode.UnprocessableEntity ||
            response.status == HttpStatusCode.PayloadTooLarge
        ) {
            val detail = runCatching { response.body<MiningErrorDto>().detail }.getOrNull()
            throw MiningParseException(detail ?: "Não foi possível interpretar o arquivo enviado.")
        }

        if (!response.status.isSuccess()) {
            logger.error("[MiningClient] parse failed: ${response.status} ${response.bodyAsText().take(500)}")
            throw MiningUnavailableException("Falha ao analisar o log (${response.status.value}).")
        }

        return response.body()
    }

    suspend fun detect(
        values: List<Double>,
        delta: Double = 0.002,
        treatment: String = "treated",
    ): DetectSeriesResponse {
        val response = try {
            client.post("$baseUrl/detect") {
                contentType(ContentType.Application.Json)
                setBody(DetectSeriesRequest(values, delta, treatment))
            }
        } catch (err: Exception) {
            logger.error("[MiningClient] $baseUrl unreachable", err)
            throw MiningUnavailableException(
                "O serviço de detecção de desvios não respondeu. Verifique se ele está no ar.",
            )
        }

        if (response.status == HttpStatusCode.UnprocessableEntity) {
            val detail = runCatching { response.body<MiningErrorDto>().detail }.getOrNull()
            throw MiningAnalysisException(detail ?: "Não foi possível analisar a série de traces.")
        }

        if (!response.status.isSuccess()) {
            logger.error("[MiningClient] detect failed: ${response.status} ${response.bodyAsText().take(500)}")
            throw MiningUnavailableException("Falha ao detectar desvios (${response.status.value}).")
        }

        return response.body()
    }

    suspend fun predictMaintenance(
        history: List<MaintenanceObservationRequest>,
    ): MaintenancePredictionResponse {
        val response = try {
            client.post("$baseUrl/predict-maintenance") {
                contentType(ContentType.Application.Json)
                setBody(MaintenancePredictionRequest(history))
            }
        } catch (err: Exception) {
            logger.error("[MiningClient] $baseUrl unreachable", err)
            throw MiningUnavailableException(
                "O serviÃ§o de prediÃ§Ã£o de manutenÃ§Ã£o nÃ£o respondeu.",
            )
        }

        if (response.status == HttpStatusCode.UnprocessableEntity) {
            val detail = runCatching { response.body<MiningErrorDto>().detail }.getOrNull()
            throw MiningAnalysisException(detail ?: "Os dados nÃ£o suportam uma previsÃ£o confiÃ¡vel.")
        }
        if (!response.status.isSuccess()) {
            logger.error("[MiningClient] prediction failed: ${response.status} ${response.bodyAsText().take(500)}")
            throw MiningUnavailableException("Falha ao calcular RUL e probabilidade (${response.status.value}).")
        }
        return response.body()
    }
}

fun Application.configureMiningClient(): MiningClient {
    val baseUrl = environment.config
        .propertyOrNull("mining.url")?.getString()
        ?: System.getenv("MINING_SERVICE_URL")
        ?: "http://localhost:8000"

    log.info("[MiningClient] process-mining service at $baseUrl")
    return MiningClient(baseUrl)
}

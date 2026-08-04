package com.deviante.dto

import com.deviante.DetectSeriesResponse
import kotlinx.serialization.Serializable

@Serializable
data class EquipmentRequest(
    val name: String,
    val tag: String? = null,
    val kind: String? = null,
    val location: String? = null,
    val description: String = "",
    val manufacturer: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val status: String = "active",
    val assetUrl: String? = null,
    val assetFormat: String? = null,
)

@Serializable
data class EquipmentResponse(
    val id: String,
    val name: String,
    val tag: String? = null,
    val kind: String? = null,
    val location: String? = null,
    val description: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val status: String,
    val assetUrl: String? = null,
    val assetFormat: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val processIds: List<String> = emptyList(),
    val processNames: List<String> = emptyList(),
    val monitoringIds: List<String> = emptyList(),
    val monitoringNames: List<String> = emptyList(),
    val parameterCount: Int = 0,
    val readingCount: Int = 0,
    val analysisCount: Int = 0,
    val latestRulValue: Double? = null,
    val latestRulUnit: String? = null,
    val latestFailureProbability: Double? = null,
    val latestAnalysisAt: String? = null,
)

@Serializable
data class MonitoringRequest(
    val name: String,
    val description: String = "",
    val sourceType: String = "manual",
    val sourceName: String? = null,
    val status: String = "active",
)

@Serializable
data class CreateMonitoringRequest(
    val name: String? = null,
)

internal fun CreateMonitoringRequest.toMonitoringRequest() = MonitoringRequest(
    name = name?.trim()?.takeIf(String::isNotBlank) ?: "Novo monitoramento",
)

@Serializable
data class MonitoringResponse(
    val id: String,
    val name: String,
    val description: String,
    val sourceType: String,
    val sourceName: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class MonitoringParameterRequest(
    val equipmentId: String,
    val name: String,
    val unit: String = "",
    val description: String = "",
)

@Serializable
data class EquipmentParameterRequest(
    val name: String,
    val unit: String = "",
    val description: String = "",
    val component: String? = null,
    val profile: String? = null,
    val baseline: Double? = null,
    val warn: Double? = null,
    val crit: Double? = null,
)

@Serializable
data class MonitoringParameterResponse(
    val id: String,
    val monitoringId: String,
    val equipmentId: String,
    val name: String,
    val unit: String,
    val description: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ReadingInput(val observedAt: String, val value: Double, val quality: String = "good")

@Serializable
data class AppendReadingsRequest(val readings: List<ReadingInput> = emptyList())

@Serializable
data class ReadingImportResponse(
    val fileName: String,
    val parameterCount: Int,
    val readingCount: Int,
)

@Serializable
data class MonitoringReadingResponse(
    val id: String,
    val parameterId: String,
    val observedAt: String,
    val value: Double,
    val quality: String,
)

@Serializable
data class AnalysisProvenanceRequest(
    val engineVersion: String? = null,
    val modelVersion: String? = null,
    val datasetVersion: String? = null,
    val codeRevision: String? = null,
    val predictionSource: String? = null,
)

@Serializable
data class RunEquipmentAnalysisRequest(
    val parameterId: String,
    val delta: Double? = null,
    val treatment: String = "treated",
    val provenance: AnalysisProvenanceRequest = AnalysisProvenanceRequest(),
)

@Serializable
data class EquipmentAnalysisResponse(
    val id: String,
    val equipmentId: String,
    val monitoringId: String,
    val parameterId: String,
    val inputSha256: String,
    val result: DetectSeriesResponse,
    val provenance: AnalysisProvenanceResponse,
    val rulValue: Double? = null,
    val rulUnit: String = "traces",
    val failureProbability: Double? = null,
    val failureHorizonValue: Double? = null,
    val failureHorizonUnit: String? = null,
    val modelVersion: String? = null,
    val recommendation: String? = null,
    val createdAt: String,
)

@Serializable
data class AnalysisProvenanceResponse(
    val sourceKind: String,
    val equipmentId: String,
    val monitoringId: String,
    val parameterId: String,
    val firstObservedAt: String? = null,
    val lastObservedAt: String? = null,
    val engineVersion: String? = null,
    val modelVersion: String? = null,
    val datasetVersion: String? = null,
    val codeRevision: String? = null,
    val predictionSource: String? = null,
)

@Serializable
data class RecommendationRequest(
    val action: String,
    val rationale: String = "",
    val priority: String = "medium",
    val status: String = "draft",
    val recommendedStart: String? = null,
    val recommendedEnd: String? = null,
)

@Serializable
data class RecommendationResponse(
    val id: String,
    val equipmentId: String,
    val analysisRunId: String? = null,
    val action: String,
    val rationale: String,
    val priority: String,
    val status: String,
    val recommendedStart: String? = null,
    val recommendedEnd: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class MaintenanceScheduleRequest(
    val equipmentId: String,
    val processId: String? = null,
    val recommendationId: String? = null,
    val title: String,
    val notes: String = "",
    val scheduledStart: String,
    val scheduledEnd: String? = null,
    val status: String = "planned",
)

@Serializable
data class MaintenanceScheduleResponse(
    val id: String,
    val equipmentId: String,
    val processId: String? = null,
    val recommendationId: String? = null,
    val title: String,
    val notes: String,
    val scheduledStart: String,
    val scheduledEnd: String? = null,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

internal fun validProbability(value: Double?): Boolean = value == null || value in 0.0..1.0
internal fun validRul(value: Double?, unit: String): Boolean = (value == null || value >= 0.0) && unit == "traces"

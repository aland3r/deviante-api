package com.deviante.dto

import com.deviante.model.AnalysisRecord
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

@Serializable
data class AnalysisTracePointResponse(
    val index: Int,
    val traceId: String,
    val caseId: String,
    val startedAt: String? = null,
    val durationSeconds: Double,
)

@Serializable
data class AnalysisDriftResponse(
    val index: Int,
    val anomalyStartIndex: Int,
    val detectionDelayTraces: Int,
    val traceId: String,
    val caseId: String,
    val durationSeconds: Double,
    val beforeMeanSeconds: Double,
    val afterMeanSeconds: Double,
    val magnitudePercent: Double,
    val windowWidth: Double,
    val estimationSeconds: Double,
)

@Serializable
data class ProcessAnalysisResponse(
    val id: String? = null,
    val name: String? = null,
    val processId: String? = null,
    val processName: String? = null,
    val eventLog: EventLogResponse,
    val method: String,
    val delta: Double,
    val traceCount: Int,
    val smoothingWindow: Int,
    val processedValues: List<Double>,
    val outlierIndexes: List<Int>,
    val points: List<AnalysisTracePointResponse>,
    val drifts: List<AnalysisDriftResponse>,
)

@Serializable
data class CreateAnalysisRequest(
    val processId: String,
    val name: String? = null,
)

/** Dashboard card — no full series payload. */
@Serializable
data class AnalysisSummaryResponse(
    val id: String,
    val name: String,
    val processId: String,
    val processName: String,
    val method: String,
    val delta: Double,
    val traceCount: Int,
    val driftCount: Int,
    val hasResult: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

fun AnalysisRecord.toSummaryResponse() = AnalysisSummaryResponse(
    id = id.toString(),
    name = name,
    processId = processId.toString(),
    processName = processName,
    method = method,
    delta = delta,
    traceCount = traceCount,
    driftCount = driftCount,
    hasResult = !resultJson.isNullOrBlank() && resultJson != "null",
    createdAt = createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    updatedAt = updatedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
)

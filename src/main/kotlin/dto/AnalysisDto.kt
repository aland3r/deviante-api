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
    /**
     * When the drift was signalled, and when the degradation is estimated to
     * have started. Maintenance is scheduled against a calendar, so a drift
     * described only by its position in the series cannot be acted on: these
     * two instants are what turn the detection into a date the Manager can
     * plan around, and their difference is the P-F interval in real time.
     */
    val detectedAt: String? = null,
    val anomalyStartedAt: String? = null,
    val detectionDelaySeconds: Double? = null,
)

/** What the series was built from — see [AnalysisRepository.latestSeries]. */
@Serializable
data class AnalysisScopeResponse(
    /**
     * `process` (whole-trace duration), `operation` (one raw label's sojourn),
     * or `activity` (sojourn of every operation mapped to one Activity).
     */
    val kind: String,
    val operationId: String? = null,
    val operationLabel: String? = null,
    val activityId: String? = null,
    val activityLabel: String? = null,
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
    /** `raw` reproduces the synthetic baseline; `treated` the shop-floor one. */
    val treatment: String = "treated",
    val scope: AnalysisScopeResponse = AnalysisScopeResponse(kind = "process"),
    val traceCount: Int,
    val smoothingWindow: Int,
    val processedValues: List<Double>,
    val outlierIndexes: List<Int>,
    val points: List<AnalysisTracePointResponse>,
    val drifts: List<AnalysisDriftResponse>,
    /**
     * Trace indexes the Manager marked "pra sumir" — desconsiderados from the
     * summary counts. Held on the persisted result so reopening an analysis
     * restores exactly where the Manager left off, instead of recomputing.
     * Empty on a fresh run; updated in place via the dismissed endpoint.
     */
    val dismissedIndexes: List<Int> = emptyList(),
)

@Serializable
data class CreateAnalysisRequest(
    val processId: String,
    val name: String? = null,
)

/** Replaces the full set of desconsiderados on a run — see the dismissed endpoint. */
@Serializable
data class UpdateDismissedRequest(
    val dismissedIndexes: List<Int> = emptyList(),
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

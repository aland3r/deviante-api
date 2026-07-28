package com.deviante.dto

import kotlinx.serialization.Serializable

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

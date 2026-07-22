package com.deviante.dto

import com.deviante.model.EventLogRecord
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

@Serializable
data class EventLogResponse(
    val id: String,
    val processId: String,
    val fileName: String,
    val format: String,
    val parseStatus: String,
    val parseError: String? = null,
    val operationCount: Int,
    val traceCount: Int,
    val uploadedAt: String,
)

/**
 * Returned right after upload: the event log plus the operations it produced,
 * each already carrying a `suggestedActivityName`. The suggestion is the raw
 * label verbatim — the system has no activity catalog to match against yet,
 * and the Manager is expected to rewrite it (translate, disambiguate) before
 * confirming the mapping.
 */
@Serializable
data class EventLogUploadResponse(
    val eventLog: EventLogResponse,
    val operations: List<UnmappedOperationResponse>,
)

@Serializable
data class UnmappedOperationResponse(
    val id: String,
    val rawLabel: String,
    val occurrenceCount: Int,
    val caseCount: Int,
    val meanDurationSeconds: Double,
    val suggestedActivityName: String,
    val activityId: String? = null,
    val activityName: String? = null,
    val mappingStatus: String,
)

@Serializable
data class ResolveMappingItem(
    val operationId: String,
    val activityName: String,
    val activityDescription: String = "",
)

@Serializable
data class ResolveMappingRequest(
    val mappings: List<ResolveMappingItem>,
)

@Serializable
data class ResolveMappingResponse(
    val mappedCount: Int,
    val activities: List<ActivityResponse>,
    val operations: List<OperationResponse>,
)

fun EventLogRecord.toResponse() = EventLogResponse(
    id = id.toString(),
    processId = processId.toString(),
    fileName = fileName,
    format = format,
    parseStatus = parseStatus,
    parseError = parseError,
    operationCount = operationCount,
    traceCount = traceCount,
    uploadedAt = uploadedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
)

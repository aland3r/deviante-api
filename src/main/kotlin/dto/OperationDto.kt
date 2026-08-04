package com.deviante.dto

import com.deviante.model.OperationRecord
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

/**
 * An operation is the log-derived component the process graph renders: it
 * carries the raw label exactly as the outsourced log wrote it, plus the
 * activity (the archetype the Manager named) it resolves to. `activityName`
 * is denormalized so the graph can label a node without a second round-trip.
 */
@Serializable
data class OperationResponse(
    val id: String,
    val eventLogId: String,
    val rawLabel: String,
    val occurrenceCount: Int,
    val activityId: String? = null,
    val activityName: String? = null,
    val mappingStatus: String,
    val createdAt: String,
    val updatedAt: String,
    val processId: String? = null,
    val processName: String? = null,
    val eventLogName: String? = null,
    val equipmentIds: List<String> = emptyList(),
    val equipmentNames: List<String> = emptyList(),
)

@Serializable
data class CreateOperationRequest(
    val rawLabel: String,
    val occurrenceCount: Int = 1,
)

@Serializable
data class MapOperationRequest(
    val activityId: String,
)

fun OperationRecord.toResponse(
    activityName: String? = null,
    processId: String? = null,
    processName: String? = null,
    eventLogName: String? = null,
    equipmentIds: List<String> = emptyList(),
    equipmentNames: List<String> = emptyList(),
) = OperationResponse(
    id = id.toString(),
    eventLogId = eventLogId.toString(),
    rawLabel = rawLabel,
    occurrenceCount = occurrenceCount,
    activityId = activityId?.toString(),
    activityName = activityName,
    mappingStatus = mappingStatus,
    createdAt = createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    updatedAt = updatedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    processId = processId,
    processName = processName,
    eventLogName = eventLogName,
    equipmentIds = equipmentIds,
    equipmentNames = equipmentNames,
)

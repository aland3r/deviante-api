package com.deviante.dto

import com.deviante.model.OperationRecord
import java.time.OffsetDateTime
import java.util.UUID

data class OperationResponse(
    val id: UUID,
    val eventLogId: UUID,
    val rawLabel: String,
    val occurrenceCount: Int,
    val activityId: UUID? = null,
    val mappingStatus: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class CreateOperationRequest(
    val rawLabel: String,
    val occurrenceCount: Int = 1,
)

data class MapOperationRequest(
    val activityId: UUID,
)

fun OperationRecord.toResponse() = OperationResponse(
    id = id,
    eventLogId = eventLogId,
    rawLabel = rawLabel,
    occurrenceCount = occurrenceCount,
    activityId = activityId,
    mappingStatus = mappingStatus,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

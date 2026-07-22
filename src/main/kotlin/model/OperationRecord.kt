package com.deviante.model

import java.time.OffsetDateTime
import java.util.UUID

data class OperationRecord(
    val id: UUID,
    val eventLogId: UUID,
    val rawLabel: String,
    val occurrenceCount: Int,
    val activityId: UUID? = null,
    val mappingStatus: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

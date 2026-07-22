package com.deviante.model

import java.time.OffsetDateTime
import java.util.UUID

data class ActivityRecord(
    val id: UUID,
    val name: String,
    val description: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

package com.deviante.dto

import com.deviante.model.ActivityRecord
import java.time.OffsetDateTime
import java.util.UUID

data class ActivityResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class CreateActivityRequest(
    val name: String,
    val description: String = "",
)

data class UpdateActivityRequest(
    val name: String,
    val description: String,
)

fun ActivityRecord.toResponse() = ActivityResponse(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

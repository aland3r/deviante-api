package com.deviante.dto

import com.deviante.model.ActivityRecord
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

@Serializable
data class ActivityResponse(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class CreateActivityRequest(
    val name: String,
    val description: String = "",
)

@Serializable
data class UpdateActivityRequest(
    val name: String,
    val description: String,
)

fun ActivityRecord.toResponse() = ActivityResponse(
    id = id.toString(),
    name = name,
    description = description,
    createdAt = createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    updatedAt = updatedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
)

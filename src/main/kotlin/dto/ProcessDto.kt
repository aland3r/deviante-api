package com.deviante.dto

import com.deviante.model.ProcessRecord
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

@Serializable
data class ProcessResponse(
    val id: String,
    val name: String,
    val companyName: String,
    val description: String,
    val sector: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class UpdateProcessRequest(
    val name: String,
    val companyName: String,
    val description: String = "",
    val sector: String = "",
)

@Serializable
data class ErrorResponse(
    val message: String,
    val fieldErrors: Map<String, String> = emptyMap(),
)

fun ProcessRecord.toResponse() = ProcessResponse(
    id = id.toString(),
    name = name,
    companyName = companyName,
    description = description,
    sector = sector,
    createdAt = createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    updatedAt = updatedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
)

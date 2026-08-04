package com.deviante.dto

import com.deviante.model.ProcessRecord
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

@Serializable
data class ProcessResponse(
    val id: String,
    val name: String,
    val description: String,
    val sector: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class UpdateProcessRequest(
    val name: String,
    val description: String = "",
    val sector: String = "",
)

@Serializable
data class RenameProcessRequest(
    val name: String,
)

const val PROCESS_DELETE_CONFIRMATION_PHRASE = "quero excluir este processo"

@Serializable
data class DeleteProcessRequest(
    val processName: String,
    val confirmationPhrase: String,
)

@Serializable
data class ErrorResponse(
    val message: String,
    val fieldErrors: Map<String, String> = emptyMap(),
)

fun validateProcessDeletion(
    request: DeleteProcessRequest,
    expectedProcessName: String,
): Map<String, String> = buildMap {
    if (request.processName != expectedProcessName) {
        put("processName", "Digite exatamente o nome do processo.")
    }
    if (request.confirmationPhrase != PROCESS_DELETE_CONFIRMATION_PHRASE) {
        put("confirmationPhrase", "Digite exatamente a frase de confirmação.")
    }
}

fun ProcessRecord.toResponse() = ProcessResponse(
    id = id.toString(),
    name = name,
    description = description,
    sector = sector,
    createdAt = createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    updatedAt = updatedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
)

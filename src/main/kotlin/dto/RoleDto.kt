package com.deviante.dto

import kotlinx.serialization.Serializable

/** Situações de uma agenda de manutenção (as mesmas do quadro da tela de agenda). */
val SCHEDULE_STATUSES = setOf("planned", "in_progress", "completed", "canceled")

@Serializable
data class ScheduleStatusRequest(
    val status: String,
    val notes: String? = null,
)

const val TRACE_NOTE_MAX_LENGTH = 2000

@Serializable
data class CreateTraceNoteRequest(
    val traceId: String,
    val activityName: String = "",
    val note: String,
)

@Serializable
data class TraceNoteResponse(
    val id: String,
    val traceId: String,
    val caseId: String? = null,
    val activityName: String? = null,
    val note: String,
    val authorName: String? = null,
    val createdAt: String,
)

/** Erro do campo da anotação, ou null quando ela é válida. */
fun traceNoteError(note: String, activityName: String): String? = when {
    note.isBlank() -> "A anotação não pode ficar em branco."
    note.trim().length > TRACE_NOTE_MAX_LENGTH -> "A anotação deve ter no máximo $TRACE_NOTE_MAX_LENGTH caracteres."
    activityName.trim().length > 255 -> "O nome da atividade deve ter no máximo 255 caracteres."
    else -> null
}

val VISIBILITY_OBJECT_TYPES = setOf("process", "equipment")

@Serializable
data class VisibilityRequest(
    val hiddenRoles: List<String> = emptyList(),
)

@Serializable
data class VisibilityResponse(
    val objectType: String,
    val objectId: String,
    val hiddenRoles: List<String>,
)

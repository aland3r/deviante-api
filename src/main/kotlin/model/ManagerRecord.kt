package com.deviante.model

import java.time.OffsetDateTime
import java.util.UUID

data class ManagerRecord(
    val id: UUID,
    val userId: UUID,
    val email: String,
    val fullName: String,
    val firstLanguage: String,
    val targetLanguage: String,
    val locationEnabled: Boolean,
    val basedIn: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

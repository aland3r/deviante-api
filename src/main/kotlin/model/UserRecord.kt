package com.deviante.model

import java.time.OffsetDateTime
import java.util.UUID

data class UserRecord(
    val id: UUID,
    val email: String,
    val passwordHash: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

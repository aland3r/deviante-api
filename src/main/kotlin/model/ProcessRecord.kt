package com.deviante.model

import java.time.OffsetDateTime
import java.util.UUID

data class ProcessRecord(
    val id: UUID,
    val managerId: UUID,
    val name: String,
    val companyName: String,
    val description: String,
    val sector: String,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

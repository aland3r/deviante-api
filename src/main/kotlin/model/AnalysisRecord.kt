package com.deviante.model

import java.time.OffsetDateTime
import java.util.UUID

data class AnalysisRecord(
    val id: UUID,
    val processId: UUID,
    val processName: String,
    val eventLogId: UUID?,
    val name: String,
    val method: String,
    val delta: Double,
    val traceCount: Int,
    val driftCount: Int,
    val smoothingWindow: Int?,
    val resultJson: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

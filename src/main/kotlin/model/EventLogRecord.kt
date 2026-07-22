package com.deviante.model

import java.time.OffsetDateTime
import java.util.UUID

data class EventLogRecord(
    val id: UUID,
    val processId: UUID,
    val fileName: String,
    val format: String, // csv or xes
    val parseStatus: String = "pending", // pending, parsing, parsed, failed
    val parseError: String? = null,
    val operationCount: Int = 0,
    val traceCount: Int = 0,
    val uploadedAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

package com.deviante.db

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb

private val tracesJson = Json { encodeDefaults = true }

object TracesTable : Table(name = "traces") {
    override val tableName = "deviante.traces"

    val id = uuid("id")
    val eventLogId = uuid("event_log_id").references(EventLogsTable.id)
    val caseId = varchar("case_id", 255)
    val eventCount = integer("event_count")
    val startedAt = timestampWithTimeZone("started_at").nullable()
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val durationSeconds = decimal("duration_seconds", 20, 3).nullable()
    /** Ordered raw labels of the trace — the variant fingerprint UC7 compares. */
    val activitySequence = jsonb<List<String>>("activity_sequence", tracesJson)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object TraceEventsTable : Table(name = "trace_events") {
    override val tableName = "deviante.trace_events"

    val id = uuid("id")
    val traceId = uuid("trace_id").references(TracesTable.id)
    val operationId = uuid("operation_id").references(OperationsTable.id)
    val sequenceIndex = integer("sequence_index")
    val occurredAt = timestampWithTimeZone("occurred_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

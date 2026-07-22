package com.deviante.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object OperationsTable : Table(name = "operations") {
    override val tableName = "deviante.operations"

    val id = uuid("id")
    val eventLogId = uuid("event_log_id").references(EventLogsTable.id)
    val rawLabel = varchar("raw_label", 255)
    val occurrenceCount = integer("occurrence_count")
    val activityId = uuid("activity_id").references(ActivitiesTable.id).nullable()
    val mappingStatus = varchar("mapping_status", 20) // unmapped, auto_mapped, mapped
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object EventLogsTable : Table(name = "event_logs") {
    override val tableName = "deviante.event_logs"

    val id = uuid("id")
    val processId = uuid("process_id").references(ProcessesTable.id)
    val fileName = varchar("file_name", 255)
    val format = varchar("format", 10) // csv, xes
    val parseStatus = varchar("parse_status", 20) // pending, parsing, parsed, failed
    val parseError = text("parse_error").nullable()
    val operationCount = integer("operation_count")
    val traceCount = integer("trace_count")
    val uploadedAt = timestampWithTimeZone("uploaded_at")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

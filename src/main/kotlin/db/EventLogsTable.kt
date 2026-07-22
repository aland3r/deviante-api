package com.deviante.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object EventLogsTable : Table(name = "event_logs") {
    override val tableName = "deviante.event_logs"

    val id = uuid("id")
    val processId = uuid("process_id").references(ProcessesTable.id)
    val fileName = varchar("file_name", 255)
    val format = varchar("format", 10) // csv or xes
    val parseStatus = varchar("parse_status", 20).default("pending") // pending, parsing, parsed, failed
    val parseError = text("parse_error").nullable()
    val operationCount = integer("operation_count").default(0)
    val traceCount = integer("trace_count").default(0)
    val uploadedAt = timestampWithTimeZone("uploaded_at")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

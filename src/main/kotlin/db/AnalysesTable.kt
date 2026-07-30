package com.deviante.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object AnalysesTable : Table(name = "analyses") {
    override val tableName = "deviante.analyses"

    val id = uuid("id")
    val processId = uuid("process_id").references(ProcessesTable.id)
    val eventLogId = uuid("event_log_id").references(EventLogsTable.id).nullable()
    val name = varchar("name", 200)
    val method = varchar("method", 50).default("adwin")
    val delta = double("delta").default(0.002)
    val traceCount = integer("trace_count").default(0)
    val driftCount = integer("drift_count").default(0)
    val smoothingWindow = integer("smoothing_window").nullable()
    val resultJson = text("result_json").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

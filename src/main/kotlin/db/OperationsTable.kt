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

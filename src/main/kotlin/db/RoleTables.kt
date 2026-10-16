package com.deviante.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Anotação de um operador ou gestor sobre um trace do processo. */
object TraceNotesTable : Table(name = "trace_notes") {
    override val tableName = "deviante.trace_notes"
    val id = uuid("id")
    val processId = uuid("process_id").references(ProcessesTable.id)
    val traceId = uuid("trace_id").references(TracesTable.id)
    val activityName = varchar("activity_name", 255).nullable()
    val note = text("note")
    val managerId = uuid("manager_id").references(ManagersTable.id)
    val businessId = uuid("business_id").references(BusinessesTable.id)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Processo ou máquina oculto para um papel (operador ou técnico). */
object ObjectVisibilityTable : Table(name = "object_visibility") {
    override val tableName = "deviante.object_visibility"
    val id = uuid("id")
    val businessId = uuid("business_id").references(BusinessesTable.id)
    val objectType = varchar("object_type", 20) // process, equipment
    val objectId = uuid("object_id")
    val hiddenRole = varchar("hidden_role", 20) // operator, technician
    val createdBy = uuid("created_by").references(ManagersTable.id).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

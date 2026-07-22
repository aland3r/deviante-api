package com.deviante.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object ManagersTable : Table(name = "managers") {
    override val tableName = "deviante.managers"

    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id)
    val email = varchar("email", 255).uniqueIndex()
    val fullName = varchar("full_name", 255)
    val role = varchar("role", 20).default("manager") // manager, owner, mentor
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

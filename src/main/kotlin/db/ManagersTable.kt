package com.deviante.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object ManagersTable : Table(name = "managers") {
    override val tableName = "deviante.managers"

    val id = uuid("id")
    val userId = uuid("user_id").references(UsersTable.id)
    val fullName = varchar("full_name", 255)
    val firstLanguage = varchar("first_language", 10)
    val targetLanguage = varchar("target_language", 10)
    val locationEnabled = bool("location_enabled")
    val basedIn = varchar("based_in", 255).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

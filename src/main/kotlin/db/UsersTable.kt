package com.deviante.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object UsersTable : Table(name = "users") {
    override val tableName = "deviante.users"

    val id = uuid("id")
    val email = varchar("email", 255)
    val passwordHash = varchar("password_hash", 255).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

package com.deviante.repository

import com.deviante.db.UsersTable
import com.deviante.model.UserRecord
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class UserRepository {
    fun findByEmail(email: String): UserRecord? = transaction {
        UsersTable
            .selectAll()
            .where { UsersTable.email eq email.lowercase() }
            .limit(1)
            .firstOrNull()
            ?.toUserRecord()
    }

    fun findById(id: UUID): UserRecord? = transaction {
        UsersTable
            .selectAll()
            .where { UsersTable.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toUserRecord()
    }

    fun existsByEmail(email: String): Boolean = findByEmail(email) != null

    fun insert(email: String, passwordHash: String): UserRecord = transaction {
        val now = OffsetDateTime.now()
        val normalizedEmail = email.trim().lowercase()
        val id = UUID.randomUUID()

        UsersTable.insert {
            it[UsersTable.id] = id
            it[UsersTable.email] = normalizedEmail
            it[UsersTable.passwordHash] = passwordHash
            it[UsersTable.createdAt] = now
            it[UsersTable.updatedAt] = now
        }

        UserRecord(
            id = id,
            email = normalizedEmail,
            passwordHash = passwordHash,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun updatePassword(id: UUID, passwordHash: String): Boolean = transaction {
        val now = OffsetDateTime.now()
        UsersTable.update({ UsersTable.id eq id }) {
            it[UsersTable.passwordHash] = passwordHash
            it[UsersTable.updatedAt] = now
        } > 0
    }

    private fun ResultRow.toUserRecord() = UserRecord(
        id = this[UsersTable.id],
        email = this[UsersTable.email],
        passwordHash = this[UsersTable.passwordHash],
        createdAt = this[UsersTable.createdAt],
        updatedAt = this[UsersTable.updatedAt],
    )
}

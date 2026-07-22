package com.deviante.repository

import com.deviante.db.ManagersTable
import com.deviante.db.UsersTable
import com.deviante.model.ManagerRecord
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID
class ManagerRepository {
    companion object {
        // Roles that can see all processes
        private val OWNER_EMAILS = setOf("design@alander.io", "alanderavila@gmail.com")
        private val MENTOR_EMAILS = setOf("pafileiro@gmail.com")
    }

    private fun detectRole(email: String): String = when {
        email.lowercase() in OWNER_EMAILS -> "owner"
        email.lowercase() in MENTOR_EMAILS -> "mentor"
        else -> "manager"
    }

    fun findByUserId(userId: UUID): ManagerRecord? = transaction {
        (ManagersTable innerJoin UsersTable)
            .selectAll()
            .where { ManagersTable.userId eq userId }
            .limit(1)
            .firstOrNull()
            ?.toManagerRecord()
    }

    /**
     * Returns the Manager for this Supabase-authenticated user, creating the
     * `users` + `managers` rows together (one transaction) on first login —
     * OAuth accounts have no password, Supabase Auth owns credentials.
     */
    fun findOrCreateForSupabaseUser(supabaseUserId: UUID, email: String, fullNameHint: String): ManagerRecord =
        transaction {
            val existing = (ManagersTable innerJoin UsersTable)
                .selectAll()
                .where { ManagersTable.userId eq supabaseUserId }
                .limit(1)
                .firstOrNull()
                ?.toManagerRecord()
            if (existing != null) {
                // Re-derive the role on every login: a manager row created
                // before its e-mail was added to OWNER_EMAILS/MENTOR_EMAILS
                // would otherwise stay stuck on the role it got at signup.
                val expected = detectRole(existing.email)
                if (existing.role == expected) return@transaction existing

                ManagersTable.update({ ManagersTable.userId eq supabaseUserId }) {
                    it[ManagersTable.role] = expected
                    it[ManagersTable.updatedAt] = OffsetDateTime.now()
                }
                return@transaction existing.copy(role = expected)
            }

            val now = OffsetDateTime.now()
            val normalizedEmail = email.trim().lowercase()

            val userExists = UsersTable.selectAll().where { UsersTable.id eq supabaseUserId }.limit(1).any()
            if (!userExists) {
                UsersTable.insert {
                    it[UsersTable.id] = supabaseUserId
                    it[UsersTable.email] = normalizedEmail
                    it[UsersTable.passwordHash] = null
                    it[UsersTable.createdAt] = now
                    it[UsersTable.updatedAt] = now
                }
            }

            val managerId = UUID.randomUUID()
            val role = detectRole(normalizedEmail)

            ManagersTable.insert {
                it[ManagersTable.id] = managerId
                it[ManagersTable.userId] = supabaseUserId
                it[ManagersTable.email] = normalizedEmail
                it[ManagersTable.fullName] = fullNameHint.ifBlank { normalizedEmail.substringBefore("@") }
                it[ManagersTable.role] = role
                it[ManagersTable.createdAt] = now
                it[ManagersTable.updatedAt] = now
            }

            ManagerRecord(
                id = managerId,
                userId = supabaseUserId,
                email = normalizedEmail,
                fullName = fullNameHint.ifBlank { normalizedEmail.substringBefore("@") },
                role = role,
                createdAt = now,
                updatedAt = now,
            )
        }

    fun update(
        userId: UUID,
        fullName: String,
    ): ManagerRecord? = transaction {
        val now = OffsetDateTime.now()
        val updated = ManagersTable.update({ ManagersTable.userId eq userId }) {
            it[ManagersTable.fullName] = fullName
            it[ManagersTable.updatedAt] = now
        }
        if (updated == 0) return@transaction null

        (ManagersTable innerJoin UsersTable)
            .selectAll()
            .where { ManagersTable.userId eq userId }
            .limit(1)
            .firstOrNull()
            ?.toManagerRecord()
    }

    private fun ResultRow.toManagerRecord() = ManagerRecord(
        id = this[ManagersTable.id],
        userId = this[ManagersTable.userId],
        email = this[UsersTable.email],
        fullName = this[ManagersTable.fullName],
        role = this[ManagersTable.role],
        createdAt = this[ManagersTable.createdAt],
        updatedAt = this[ManagersTable.updatedAt],
    )
}

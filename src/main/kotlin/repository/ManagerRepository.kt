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

private const val DEFAULT_FIRST_LANGUAGE = "pt"
private const val DEFAULT_TARGET_LANGUAGE = "en"

class ManagerRepository {
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
            if (existing != null) return@transaction existing

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
            ManagersTable.insert {
                it[ManagersTable.id] = managerId
                it[ManagersTable.userId] = supabaseUserId
                it[ManagersTable.fullName] = fullNameHint.ifBlank { normalizedEmail.substringBefore("@") }
                it[ManagersTable.firstLanguage] = DEFAULT_FIRST_LANGUAGE
                it[ManagersTable.targetLanguage] = DEFAULT_TARGET_LANGUAGE
                it[ManagersTable.locationEnabled] = false
                it[ManagersTable.basedIn] = null
                it[ManagersTable.createdAt] = now
                it[ManagersTable.updatedAt] = now
            }

            ManagerRecord(
                id = managerId,
                userId = supabaseUserId,
                email = normalizedEmail,
                fullName = fullNameHint.ifBlank { normalizedEmail.substringBefore("@") },
                firstLanguage = DEFAULT_FIRST_LANGUAGE,
                targetLanguage = DEFAULT_TARGET_LANGUAGE,
                locationEnabled = false,
                basedIn = null,
                createdAt = now,
                updatedAt = now,
            )
        }

    fun update(
        userId: UUID,
        fullName: String,
        firstLanguage: String,
        targetLanguage: String,
        locationEnabled: Boolean,
        basedIn: String?,
    ): ManagerRecord? = transaction {
        val now = OffsetDateTime.now()
        val updated = ManagersTable.update({ ManagersTable.userId eq userId }) {
            it[ManagersTable.fullName] = fullName
            it[ManagersTable.firstLanguage] = firstLanguage
            it[ManagersTable.targetLanguage] = targetLanguage
            it[ManagersTable.locationEnabled] = locationEnabled
            it[ManagersTable.basedIn] = if (locationEnabled) basedIn else null
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
        firstLanguage = this[ManagersTable.firstLanguage],
        targetLanguage = this[ManagersTable.targetLanguage],
        locationEnabled = this[ManagersTable.locationEnabled],
        basedIn = this[ManagersTable.basedIn],
        createdAt = this[ManagersTable.createdAt],
        updatedAt = this[ManagersTable.updatedAt],
    )
}

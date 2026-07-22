package com.deviante.repository

import com.deviante.db.ActivitiesTable
import com.deviante.db.ProcessActivitiesTable
import com.deviante.model.ActivityRecord
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class ActivitiesRepository {
    fun listAll(): List<ActivityRecord> = transaction {
        ActivitiesTable
            .selectAll()
            .orderBy(ActivitiesTable.name, SortOrder.ASC)
            .map { it.toActivityRecord() }
    }

    fun listForProcess(processId: UUID): List<ActivityRecord> = transaction {
        ProcessActivitiesTable
            .selectAll()
            .where { ProcessActivitiesTable.processId eq processId }
            .join(ActivitiesTable, { ProcessActivitiesTable.activityId }, { ActivitiesTable.id })
            .selectAll()
            .map { it.toActivityRecord() }
    }

    fun findById(id: UUID): ActivityRecord? = transaction {
        ActivitiesTable
            .selectAll()
            .where { ActivitiesTable.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toActivityRecord()
    }

    fun create(name: String, description: String): ActivityRecord = transaction {
        val now = OffsetDateTime.now()
        val id = UUID.randomUUID()

        ActivitiesTable.insert {
            it[ActivitiesTable.id] = id
            it[ActivitiesTable.name] = name
            it[ActivitiesTable.description] = description
            it[ActivitiesTable.createdAt] = now
            it[ActivitiesTable.updatedAt] = now
        }

        ActivityRecord(
            id = id,
            name = name,
            description = description,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun update(id: UUID, name: String, description: String): ActivityRecord? = transaction {
        val now = OffsetDateTime.now()
        val updated = ActivitiesTable.update({ ActivitiesTable.id eq id }) {
            it[ActivitiesTable.name] = name
            it[ActivitiesTable.description] = description
            it[ActivitiesTable.updatedAt] = now
        }
        if (updated == 0) return@transaction null

        findById(id)
    }

    fun linkToProcess(processId: UUID, activityId: UUID): Boolean = transaction {
        val now = OffsetDateTime.now()
        val inserted = ProcessActivitiesTable.insert {
            it[ProcessActivitiesTable.processId] = processId
            it[ProcessActivitiesTable.activityId] = activityId
            it[ProcessActivitiesTable.createdAt] = now
        } > 0
        inserted
    }

    private fun ResultRow.toActivityRecord() = ActivityRecord(
        id = this[ActivitiesTable.id],
        name = this[ActivitiesTable.name],
        description = this[ActivitiesTable.description],
        createdAt = this[ActivitiesTable.createdAt],
        updatedAt = this[ActivitiesTable.updatedAt],
    )
}

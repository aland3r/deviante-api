package com.deviante.repository

import com.deviante.db.ActivitiesTable
import com.deviante.db.OperationsTable
import com.deviante.db.ProcessActivitiesTable
import com.deviante.model.ActivityRecord
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

enum class ActivityDeletion { DELETED, NOT_FOUND, IN_USE }

class ActivitiesRepository {
    /** Catálogo de atividades de uma empresa. */
    fun listAll(businessId: UUID): List<ActivityRecord> = transaction {
        ActivitiesTable
            .selectAll()
            .where { ActivitiesTable.businessId eq businessId }
            .orderBy(ActivitiesTable.name, SortOrder.ASC)
            .map { it.toActivityRecord() }
    }

    fun listForProcess(processId: UUID): List<ActivityRecord> = transaction {
        ProcessActivitiesTable
            .join(ActivitiesTable, JoinType.INNER, ProcessActivitiesTable.activityId, ActivitiesTable.id)
            .selectAll()
            .where { ProcessActivitiesTable.processId eq processId }
            .orderBy(ActivitiesTable.name, SortOrder.ASC)
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

    fun create(name: String, description: String, businessId: UUID): ActivityRecord = transaction {
        val now = OffsetDateTime.now()
        val id = UUID.randomUUID()

        ActivitiesTable.insert {
            it[ActivitiesTable.id] = id
            it[ActivitiesTable.businessId] = businessId
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
            businessId = businessId,
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

    /**
     * Exclui a atividade. Os vínculos com processos caem em cascata no banco;
     * se alguma operação de log estiver mapeada para ela, a exclusão é recusada.
     */
    fun delete(id: UUID): ActivityDeletion = transaction {
        val inUse = OperationsTable.selectAll().where { OperationsTable.activityId eq id }.limit(1).any()
        if (inUse) return@transaction ActivityDeletion.IN_USE
        val deleted = ActivitiesTable.deleteWhere { ActivitiesTable.id eq id }
        if (deleted > 0) ActivityDeletion.DELETED else ActivityDeletion.NOT_FOUND
    }

    fun linkToProcess(processId: UUID, activityId: UUID): Boolean = transaction {
        val now = OffsetDateTime.now()
        ProcessActivitiesTable.insertIgnore {
            it[ProcessActivitiesTable.processId] = processId
            it[ProcessActivitiesTable.activityId] = activityId
            it[ProcessActivitiesTable.createdAt] = now
        }.insertedCount > 0
    }

    fun unlinkFromProcess(processId: UUID, activityId: UUID): Boolean = transaction {
        ProcessActivitiesTable.deleteWhere {
            (ProcessActivitiesTable.processId eq processId) and
                (ProcessActivitiesTable.activityId eq activityId)
        } > 0
    }

    private fun ResultRow.toActivityRecord() = ActivityRecord(
        id = this[ActivitiesTable.id],
        name = this[ActivitiesTable.name],
        description = this[ActivitiesTable.description],
        createdAt = this[ActivitiesTable.createdAt],
        updatedAt = this[ActivitiesTable.updatedAt],
        businessId = this[ActivitiesTable.businessId],
    )
}

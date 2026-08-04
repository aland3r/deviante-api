package com.deviante.repository

import com.deviante.db.OperationsTable
import com.deviante.db.EventLogsTable
import com.deviante.db.ProcessesTable
import com.deviante.model.OperationRecord
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

class OperationsRepository {
    fun listForManager(managerId: UUID): List<OperationRecord> = transaction {
        OperationsTable.innerJoin(EventLogsTable).innerJoin(ProcessesTable)
            .selectAll()
            .where { ProcessesTable.managerId eq managerId }
            .orderBy(OperationsTable.occurrenceCount, SortOrder.DESC)
            .map { it.toOperationRecord() }
    }

    fun listForEventLog(eventLogId: UUID): List<OperationRecord> = transaction {
        OperationsTable
            .selectAll()
            .where { OperationsTable.eventLogId eq eventLogId }
            .orderBy(OperationsTable.occurrenceCount, SortOrder.DESC)
            .map { it.toOperationRecord() }
    }

    fun findById(id: UUID): OperationRecord? = transaction {
        OperationsTable
            .selectAll()
            .where { OperationsTable.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toOperationRecord()
    }

    fun create(
        eventLogId: UUID,
        rawLabel: String,
        occurrenceCount: Int = 1,
    ): OperationRecord = transaction {
        val now = OffsetDateTime.now()
        val id = UUID.randomUUID()

        OperationsTable.insert {
            it[OperationsTable.id] = id
            it[OperationsTable.eventLogId] = eventLogId
            it[OperationsTable.rawLabel] = rawLabel
            it[OperationsTable.occurrenceCount] = occurrenceCount
            it[OperationsTable.mappingStatus] = "unmapped"
            it[OperationsTable.createdAt] = now
            it[OperationsTable.updatedAt] = now
        }

        OperationRecord(
            id = id,
            eventLogId = eventLogId,
            rawLabel = rawLabel,
            occurrenceCount = occurrenceCount,
            mappingStatus = "unmapped",
            createdAt = now,
            updatedAt = now,
        )
    }

    fun mapToActivity(id: UUID, activityId: UUID): OperationRecord? = transaction {
        val now = OffsetDateTime.now()
        val updated = OperationsTable.update({ OperationsTable.id eq id }) {
            it[OperationsTable.activityId] = activityId
            it[OperationsTable.mappingStatus] = "mapped"
            it[OperationsTable.updatedAt] = now
        }
        if (updated == 0) return@transaction null

        findById(id)
    }

    fun unmap(id: UUID): OperationRecord? = transaction {
        val now = OffsetDateTime.now()
        val updated = OperationsTable.update({ OperationsTable.id eq id }) {
            it[OperationsTable.activityId] = null
            it[OperationsTable.mappingStatus] = "unmapped"
            it[OperationsTable.updatedAt] = now
        }
        if (updated == 0) return@transaction null

        findById(id)
    }

    private fun ResultRow.toOperationRecord() = OperationRecord(
        id = this[OperationsTable.id],
        eventLogId = this[OperationsTable.eventLogId],
        rawLabel = this[OperationsTable.rawLabel],
        occurrenceCount = this[OperationsTable.occurrenceCount],
        activityId = this[OperationsTable.activityId],
        mappingStatus = this[OperationsTable.mappingStatus],
        createdAt = this[OperationsTable.createdAt],
        updatedAt = this[OperationsTable.updatedAt],
    )
}

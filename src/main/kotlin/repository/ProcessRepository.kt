package com.deviante.repository

import com.deviante.db.ProcessesTable
import com.deviante.model.ProcessRecord
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.util.UUID

private const val DEFAULT_PROCESS_NAME = "Untitled"

class ProcessRepository {
    fun listAll(): List<ProcessRecord> = transaction {
        ProcessesTable
            .selectAll()
            .orderBy(ProcessesTable.updatedAt, SortOrder.DESC)
            .map { it.toProcessRecord() }
    }

    fun findById(id: UUID): ProcessRecord? = transaction {
        ProcessesTable
            .selectAll()
            .where { ProcessesTable.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toProcessRecord()
    }

    /** Figma-style instant creation — default name, no form required up front. */
    fun create(managerId: UUID): ProcessRecord = transaction {
        val now = OffsetDateTime.now()
        val id = UUID.randomUUID()

        ProcessesTable.insert {
            it[ProcessesTable.id] = id
            it[ProcessesTable.managerId] = managerId
            it[ProcessesTable.name] = DEFAULT_PROCESS_NAME
            it[ProcessesTable.companyName] = ""
            it[ProcessesTable.description] = ""
            it[ProcessesTable.sector] = ""
            it[ProcessesTable.createdAt] = now
            it[ProcessesTable.updatedAt] = now
        }

        ProcessRecord(
            id = id,
            managerId = managerId,
            name = DEFAULT_PROCESS_NAME,
            companyName = "",
            description = "",
            sector = "",
            createdAt = now,
            updatedAt = now,
        )
    }

    fun update(
        id: UUID,
        name: String,
        description: String,
        sector: String,
    ): ProcessRecord? = transaction {
        val now = OffsetDateTime.now()
        val updated = ProcessesTable.update({ ProcessesTable.id eq id }) {
            it[ProcessesTable.name] = name
            it[ProcessesTable.companyName] = ""
            it[ProcessesTable.description] = description
            it[ProcessesTable.sector] = sector
            it[ProcessesTable.updatedAt] = now
        }
        if (updated == 0) return@transaction null

        findById(id)
    }

    fun updateName(id: UUID, name: String): ProcessRecord? = transaction {
        val updated = ProcessesTable.update({ ProcessesTable.id eq id }) {
            it[ProcessesTable.name] = name
            it[ProcessesTable.updatedAt] = OffsetDateTime.now()
        }
        if (updated == 0) return@transaction null

        findById(id)
    }

    fun delete(id: UUID): Boolean = transaction {
        ProcessesTable.deleteWhere { ProcessesTable.id eq id } > 0
    }

    private fun ResultRow.toProcessRecord() = ProcessRecord(
        id = this[ProcessesTable.id],
        managerId = this[ProcessesTable.managerId],
        name = this[ProcessesTable.name],
        companyName = this[ProcessesTable.companyName],
        description = this[ProcessesTable.description],
        sector = this[ProcessesTable.sector],
        createdAt = this[ProcessesTable.createdAt],
        updatedAt = this[ProcessesTable.updatedAt],
    )
}

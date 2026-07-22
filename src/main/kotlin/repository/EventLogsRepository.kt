package com.deviante.repository

import com.deviante.db.EventLogsTable
import com.deviante.model.EventLogRecord
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

class EventLogsRepository {
    fun create(
        processId: UUID,
        fileName: String,
        format: String,
        parseStatus: String = "pending",
        operationCount: Int = 0,
        traceCount: Int = 0,
    ): EventLogRecord = transaction {
        val now = OffsetDateTime.now()
        val id = UUID.randomUUID()

        EventLogsTable.insert {
            it[EventLogsTable.id] = id
            it[EventLogsTable.processId] = processId
            it[EventLogsTable.fileName] = fileName
            it[EventLogsTable.format] = format
            it[EventLogsTable.parseStatus] = parseStatus
            it[EventLogsTable.parseError] = null
            it[EventLogsTable.operationCount] = operationCount
            it[EventLogsTable.traceCount] = traceCount
            it[EventLogsTable.uploadedAt] = now
            it[EventLogsTable.createdAt] = now
            it[EventLogsTable.updatedAt] = now
        }

        EventLogRecord(
            id = id,
            processId = processId,
            fileName = fileName,
            format = format,
            parseStatus = parseStatus,
            operationCount = operationCount,
            traceCount = traceCount,
            uploadedAt = now,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun findByProcessId(processId: UUID): List<EventLogRecord> = transaction {
        EventLogsTable
            .selectAll()
            .where { EventLogsTable.processId eq processId }
            .map { it.toEventLogRecord() }
    }

    private fun ResultRow.toEventLogRecord() = EventLogRecord(
        id = this[EventLogsTable.id],
        processId = this[EventLogsTable.processId],
        fileName = this[EventLogsTable.fileName],
        format = this[EventLogsTable.format],
        parseStatus = this[EventLogsTable.parseStatus],
        parseError = this[EventLogsTable.parseError],
        operationCount = this[EventLogsTable.operationCount],
        traceCount = this[EventLogsTable.traceCount],
        uploadedAt = this[EventLogsTable.uploadedAt],
        createdAt = this[EventLogsTable.createdAt],
        updatedAt = this[EventLogsTable.updatedAt],
    )
}

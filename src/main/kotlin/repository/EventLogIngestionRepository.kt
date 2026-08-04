package com.deviante.repository

import com.deviante.ParsedLogDto
import com.deviante.db.EventLogsTable
import com.deviante.db.OperationsTable
import com.deviante.db.TraceEventsTable
import com.deviante.db.TracesTable
import com.deviante.model.EventLogRecord
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Persists a parse result from the mining service (UC4).
 *
 * Everything lands in **one transaction**: a half-written log — operations
 * without their traces, say — would show the Manager a mapping screen for a
 * process whose traces never arrived, and there is no partial state in the
 * schema to represent that honestly.
 */
class EventLogIngestionRepository {

    data class Ingested(
        val eventLog: EventLogRecord,
        val operationIdsByLabel: Map<String, UUID>,
    )

    fun ingest(processId: UUID, fileName: String, parsed: ParsedLogDto): Ingested = transaction {
        val now = OffsetDateTime.now()
        val eventLogId = UUID.randomUUID()

        EventLogsTable.insert {
            it[EventLogsTable.id] = eventLogId
            it[EventLogsTable.processId] = processId
            it[EventLogsTable.fileName] = fileName
            it[EventLogsTable.format] = parsed.format
            it[EventLogsTable.parseStatus] = "parsed"
            it[EventLogsTable.parseError] = null
            it[EventLogsTable.operationCount] = parsed.operationCount
            it[EventLogsTable.traceCount] = parsed.traceCount
            it[EventLogsTable.uploadedAt] = now
            it[EventLogsTable.createdAt] = now
            it[EventLogsTable.updatedAt] = now
        }

        // One operation per distinct raw label. The Manager never edits these
        // labels — they are what the outsourced log said — only the activity
        // each one resolves to (UC5/UC6).
        val operationIdsByLabel = mutableMapOf<String, UUID>()
        for (event in parsed.events) {
            val operationId = UUID.randomUUID()
            operationIdsByLabel[event.rawLabel] = operationId

            OperationsTable.insert {
                it[OperationsTable.id] = operationId
                it[OperationsTable.eventLogId] = eventLogId
                it[OperationsTable.rawLabel] = event.rawLabel.take(255)
                it[OperationsTable.occurrenceCount] = event.occurrenceCount
                it[OperationsTable.activityId] = null
                it[OperationsTable.mappingStatus] = "unmapped"
                it[OperationsTable.createdAt] = now
                it[OperationsTable.updatedAt] = now
            }
        }

        val traceIds = parsed.traces.map { UUID.randomUUID() }

        TracesTable.batchInsert(parsed.traces.withIndex(), shouldReturnGeneratedValues = false) { (index, trace) ->
            this[TracesTable.id] = traceIds[index]
            this[TracesTable.eventLogId] = eventLogId
            this[TracesTable.caseId] = trace.caseId.take(255)
            this[TracesTable.eventCount] = trace.eventCount
            this[TracesTable.startedAt] = parseTimestamp(trace.startedAt)
            this[TracesTable.endedAt] = parseTimestamp(trace.endedAt)
            this[TracesTable.durationSeconds] = trace.durationSeconds?.let { BigDecimal.valueOf(it) }
            this[TracesTable.activitySequence] = trace.events.map { it.rawLabel }
            this[TracesTable.createdAt] = now
            this[TracesTable.updatedAt] = now
        }

        // Flattened so a real log (Prod1Torno.csv is ~13k events) is a single
        // batched round-trip rather than one per trace.
        val traceEvents = parsed.traces.flatMapIndexed { traceIndex, trace ->
            trace.events.mapNotNull { event ->
                val operationId = operationIdsByLabel[event.rawLabel] ?: return@mapNotNull null
                Triple(traceIds[traceIndex], operationId, event)
            }
        }

        TraceEventsTable.batchInsert(traceEvents, shouldReturnGeneratedValues = false) { (traceId, operationId, event) ->
            this[TraceEventsTable.id] = UUID.randomUUID()
            this[TraceEventsTable.traceId] = traceId
            this[TraceEventsTable.operationId] = operationId
            this[TraceEventsTable.sequenceIndex] = event.sequenceIndex
            this[TraceEventsTable.occurredAt] = parseTimestamp(event.occurredAt)
            this[TraceEventsTable.durationSeconds] = event.durationSeconds?.let { BigDecimal.valueOf(it) }
            this[TraceEventsTable.createdAt] = now
        }

        Ingested(
            eventLog = EventLogRecord(
                id = eventLogId,
                processId = processId,
                fileName = fileName,
                format = parsed.format,
                parseStatus = "parsed",
                operationCount = parsed.operationCount,
                traceCount = parsed.traceCount,
                uploadedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
            operationIdsByLabel = operationIdsByLabel,
        )
    }

    /** Records a failed parse so the process keeps a trail of bad uploads. */
    fun recordFailure(processId: UUID, fileName: String, format: String, error: String): EventLogRecord =
        transaction {
            val now = OffsetDateTime.now()
            val id = UUID.randomUUID()

            EventLogsTable.insert {
                it[EventLogsTable.id] = id
                it[EventLogsTable.processId] = processId
                it[EventLogsTable.fileName] = fileName
                it[EventLogsTable.format] = format
                it[EventLogsTable.parseStatus] = "failed"
                it[EventLogsTable.parseError] = error.take(2000)
                it[EventLogsTable.operationCount] = 0
                it[EventLogsTable.traceCount] = 0
                it[EventLogsTable.uploadedAt] = now
                it[EventLogsTable.createdAt] = now
                it[EventLogsTable.updatedAt] = now
            }

            EventLogRecord(
                id = id,
                processId = processId,
                fileName = fileName,
                format = format,
                parseStatus = "failed",
                parseError = error,
                uploadedAt = now,
                createdAt = now,
                updatedAt = now,
            )
        }

    private fun parseTimestamp(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

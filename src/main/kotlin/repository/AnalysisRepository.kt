package com.deviante.repository

import com.deviante.db.AnalysesTable
import com.deviante.db.EventLogsTable
import com.deviante.db.ProcessesTable
import com.deviante.db.TracesTable
import com.deviante.dto.AnalysisTracePointResponse
import com.deviante.dto.ProcessAnalysisResponse
import com.deviante.model.AnalysisRecord
import com.deviante.model.EventLogRecord
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class AnalysisSeries(
    val eventLog: EventLogRecord,
    val points: List<AnalysisTracePointResponse>,
)

class AnalysisRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun latestSeries(processId: UUID): AnalysisSeries? = transaction {
        val log = EventLogsTable
            .selectAll()
            .where {
                (EventLogsTable.processId eq processId) and
                    (EventLogsTable.parseStatus eq "parsed")
            }
            .orderBy(EventLogsTable.uploadedAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toEventLogRecord()
            ?: return@transaction null

        val rows = TracesTable
            .selectAll()
            .where { TracesTable.eventLogId eq log.id }
            .mapNotNull { row ->
                val duration = row[TracesTable.durationSeconds]?.toDouble()
                if (duration == null || !duration.isFinite() || duration < 0) {
                    null
                } else {
                    TraceDurationRow(
                        id = row[TracesTable.id],
                        caseId = row[TracesTable.caseId],
                        startedAt = row[TracesTable.startedAt],
                        durationSeconds = duration,
                    )
                }
            }
            .sortedWith(
                compareBy<TraceDurationRow> { it.startedAt ?: OffsetDateTime.MAX }
                    .thenBy { it.caseId },
            )

        AnalysisSeries(
            eventLog = log,
            points = rows.mapIndexed { index, row ->
                AnalysisTracePointResponse(
                    index = index + 1,
                    traceId = row.id.toString(),
                    caseId = row.caseId,
                    startedAt = row.startedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    durationSeconds = row.durationSeconds,
                )
            },
        )
    }

    fun listSummaries(): List<AnalysisRecord> = transaction {
        AnalysesTable
            .join(ProcessesTable, JoinType.INNER, AnalysesTable.processId, ProcessesTable.id)
            .selectAll()
            .orderBy(AnalysesTable.updatedAt, SortOrder.DESC)
            .map { it.toAnalysisRecord() }
    }

    fun findById(id: UUID): AnalysisRecord? = transaction {
        AnalysesTable
            .join(ProcessesTable, JoinType.INNER, AnalysesTable.processId, ProcessesTable.id)
            .selectAll()
            .where { AnalysesTable.id eq id }
            .firstOrNull()
            ?.toAnalysisRecord()
    }

    fun createStub(processId: UUID, processName: String, name: String? = null): AnalysisRecord = transaction {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        val analysisName = name?.takeIf { it.isNotBlank() } ?: "Análise — $processName"
        AnalysesTable.insert {
            it[AnalysesTable.id] = id
            it[AnalysesTable.processId] = processId
            it[AnalysesTable.eventLogId] = null
            it[AnalysesTable.name] = analysisName
            it[AnalysesTable.method] = "adwin"
            it[AnalysesTable.delta] = 0.002
            it[AnalysesTable.traceCount] = 0
            it[AnalysesTable.driftCount] = 0
            it[AnalysesTable.smoothingWindow] = null
            it[AnalysesTable.resultJson] = null
            it[AnalysesTable.createdAt] = now
            it[AnalysesTable.updatedAt] = now
        }
        findById(id)!!
    }

    fun saveRun(
        processId: UUID,
        processName: String,
        analysisId: UUID?,
        response: ProcessAnalysisResponse,
    ): AnalysisRecord = transaction {
        val now = OffsetDateTime.now()
        val eventLogId = runCatching { UUID.fromString(response.eventLog.id) }.getOrNull()
        val payload = json.encodeToString(
            response.copy(
                id = null,
                name = null,
                processId = null,
                processName = null,
            ),
        )
        val analysisName = "Análise — $processName"
        val targetId = analysisId ?: UUID.randomUUID()
        val existing = analysisId?.let { id ->
            AnalysesTable.selectAll().where { AnalysesTable.id eq id }.firstOrNull()
        }

        if (existing != null) {
            AnalysesTable.update({ AnalysesTable.id eq targetId }) {
                it[AnalysesTable.eventLogId] = eventLogId
                it[AnalysesTable.name] = existing[AnalysesTable.name].ifBlank { analysisName }
                it[AnalysesTable.method] = response.method
                it[AnalysesTable.delta] = response.delta
                it[AnalysesTable.traceCount] = response.traceCount
                it[AnalysesTable.driftCount] = response.drifts.size
                it[AnalysesTable.smoothingWindow] = response.smoothingWindow
                it[AnalysesTable.resultJson] = payload
                it[AnalysesTable.updatedAt] = now
            }
        } else {
            AnalysesTable.insert {
                it[AnalysesTable.id] = targetId
                it[AnalysesTable.processId] = processId
                it[AnalysesTable.eventLogId] = eventLogId
                it[AnalysesTable.name] = analysisName
                it[AnalysesTable.method] = response.method
                it[AnalysesTable.delta] = response.delta
                it[AnalysesTable.traceCount] = response.traceCount
                it[AnalysesTable.driftCount] = response.drifts.size
                it[AnalysesTable.smoothingWindow] = response.smoothingWindow
                it[AnalysesTable.resultJson] = payload
                it[AnalysesTable.createdAt] = now
                it[AnalysesTable.updatedAt] = now
            }
        }

        findById(targetId)!!
    }

    fun decodeResult(record: AnalysisRecord): ProcessAnalysisResponse? {
        val raw = record.resultJson?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        return runCatching {
            json.decodeFromString<ProcessAnalysisResponse>(raw).copy(
                id = record.id.toString(),
                name = record.name,
                processId = record.processId.toString(),
                processName = record.processName,
            )
        }.getOrNull()
    }

    private data class TraceDurationRow(
        val id: UUID,
        val caseId: String,
        val startedAt: OffsetDateTime?,
        val durationSeconds: Double,
    )

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

    private fun ResultRow.toAnalysisRecord() = AnalysisRecord(
        id = this[AnalysesTable.id],
        processId = this[AnalysesTable.processId],
        processName = this[ProcessesTable.name],
        eventLogId = this[AnalysesTable.eventLogId],
        name = this[AnalysesTable.name],
        method = this[AnalysesTable.method],
        delta = this[AnalysesTable.delta],
        traceCount = this[AnalysesTable.traceCount],
        driftCount = this[AnalysesTable.driftCount],
        smoothingWindow = this[AnalysesTable.smoothingWindow],
        resultJson = this[AnalysesTable.resultJson],
        createdAt = this[AnalysesTable.createdAt],
        updatedAt = this[AnalysesTable.updatedAt],
    )
}

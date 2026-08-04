package com.deviante.repository

import com.deviante.db.AnalysesTable
import com.deviante.db.EventLogsTable
import com.deviante.db.OperationsTable
import com.deviante.db.ProcessesTable
import com.deviante.db.TraceEventsTable
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

/** Raised when the caller asked for a series the stored log cannot produce. */
class AnalysisSeriesException(message: String) : RuntimeException(message)

class AnalysisRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Builds the series ADWIN will consume from the process's most recent log.
     *
     * With [operationId] null the series is the **duration of each trace** —
     * how long a whole production case took. That is the process-level view
     * and the only one the product could offer before per-event durations were
     * persisted.
     *
     * With [operationId] set the series is the **sojourn time of that single
     * operation**, one observation per trace, which is what the research
     * scripts analyse when they filter `Machine_Operating` out of the log.
     * Reproducing their numbers requires this scope; the process-level series
     * mixes every activity of the case and is a different signal.
     *
     * Either way the series is ordered by when the trace started, so the index
     * ADWIN reports is a position in time and not an arbitrary row order.
     */
    fun latestSeries(
        processId: UUID,
        operationId: UUID? = null,
        activityId: UUID? = null,
    ): AnalysisSeries? = transaction {
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

        if (operationId != null) {
            return@transaction AnalysisSeries(
                eventLog = log,
                points = operationSeries(log.id, operationId),
            )
        }

        if (activityId != null) {
            return@transaction AnalysisSeries(
                eventLog = log,
                points = activitySeries(log.id, activityId),
            )
        }

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

    /**
     * One observation per trace: the sojourn time of [operationId] in it.
     *
     * A trace that never ran the operation contributes nothing — it is absent
     * from the series rather than present as a zero, because a zero would read
     * to ADWIN as an abrupt drop to idle. When the same operation occurs more
     * than once in a trace the durations are summed, which matches how the
     * research scripts collapse the lifecycle pairs of a repeated activity.
     */
    private fun operationSeries(eventLogId: UUID, operationId: UUID): List<AnalysisTracePointResponse> {
        val operation = OperationsTable
            .selectAll()
            .where { OperationsTable.id eq operationId }
            .firstOrNull()
            ?: throw AnalysisSeriesException("Operação não encontrada neste log.")

        if (operation[OperationsTable.eventLogId] != eventLogId) {
            throw AnalysisSeriesException(
                "A operação escolhida pertence a outro log de eventos. " +
                    "Selecione uma operação do log mais recente do processo.",
            )
        }

        return aggregatedSeries(
            eventLogId = eventLogId,
            operationIds = listOf(operationId),
            missingEventsMessage = "Nenhum evento desta operação foi encontrado no log.",
            scopeNoun = "operação",
        )
    }

    /**
     * One observation per trace, aggregating the sojourn time of **every
     * operation the Activity resolves to** (many raw aliases can map to one
     * Activity). A trace that never ran any of them is absent from the series,
     * for the same reason a single operation's zero would mislead ADWIN.
     */
    private fun activitySeries(eventLogId: UUID, activityId: UUID): List<AnalysisTracePointResponse> {
        val operationIds = OperationsTable
            .selectAll()
            .where {
                (OperationsTable.eventLogId eq eventLogId) and
                    (OperationsTable.activityId eq activityId)
            }
            .map { it[OperationsTable.id] }

        if (operationIds.isEmpty()) {
            throw AnalysisSeriesException(
                "Nenhuma operação deste log está mapeada para a atividade escolhida.",
            )
        }

        return aggregatedSeries(
            eventLogId = eventLogId,
            operationIds = operationIds,
            missingEventsMessage = "Nenhum evento desta atividade foi encontrado no log.",
            scopeNoun = "atividade",
        )
    }

    /**
     * Sums the per-event durations of [operationIds] into one observation per
     * trace, ordered by when the trace started so ADWIN's index is a position
     * in time. Shared by the operation and activity scopes.
     */
    private fun aggregatedSeries(
        eventLogId: UUID,
        operationIds: List<UUID>,
        missingEventsMessage: String,
        scopeNoun: String,
    ): List<AnalysisTracePointResponse> {
        val rows = TraceEventsTable
            .join(TracesTable, JoinType.INNER, TraceEventsTable.traceId, TracesTable.id)
            .selectAll()
            .where {
                (TraceEventsTable.operationId inList operationIds) and
                    (TracesTable.eventLogId eq eventLogId)
            }
            .toList()

        if (rows.isEmpty()) {
            throw AnalysisSeriesException(missingEventsMessage)
        }

        // Logs ingested before `trace_events.duration_seconds` existed carry no
        // per-event duration. Refusing here is deliberate: silently dropping
        // them would present a truncated series as if it were the whole log.
        if (rows.all { it[TraceEventsTable.durationSeconds] == null }) {
            throw AnalysisSeriesException(
                "Este log foi carregado antes de o sistema passar a guardar a duração de cada evento. " +
                    "Envie o arquivo novamente para analisar por $scopeNoun.",
            )
        }

        data class Accumulated(
            val traceId: UUID,
            val caseId: String,
            val startedAt: OffsetDateTime?,
            var totalSeconds: Double,
        )

        val byTrace = LinkedHashMap<UUID, Accumulated>()
        for (row in rows) {
            val duration = row[TraceEventsTable.durationSeconds]?.toDouble() ?: continue
            if (!duration.isFinite() || duration < 0) continue
            val traceId = row[TracesTable.id]
            val accumulated = byTrace.getOrPut(traceId) {
                Accumulated(
                    traceId = traceId,
                    caseId = row[TracesTable.caseId],
                    startedAt = row[TracesTable.startedAt],
                    totalSeconds = 0.0,
                )
            }
            accumulated.totalSeconds += duration
        }

        return byTrace.values
            .sortedWith(
                compareBy<Accumulated> { it.startedAt ?: OffsetDateTime.MAX }
                    .thenBy { it.caseId },
            )
            .mapIndexed { index, accumulated ->
                AnalysisTracePointResponse(
                    index = index + 1,
                    traceId = accumulated.traceId.toString(),
                    caseId = accumulated.caseId,
                    startedAt = accumulated.startedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    durationSeconds = accumulated.totalSeconds,
                )
            }
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

    /**
     * Persist the Manager's desconsiderados on an existing run without
     * recomputing it: decode the stored result, replace [dismissedIndexes],
     * and write it back. Returns false when there is no run to update yet.
     */
    fun updateDismissed(id: UUID, dismissedIndexes: List<Int>): Boolean = transaction {
        val record = findById(id) ?: return@transaction false
        val decoded = decodeResult(record) ?: return@transaction false
        val payload = json.encodeToString(
            decoded.copy(
                id = null,
                name = null,
                processId = null,
                processName = null,
                dismissedIndexes = dismissedIndexes.distinct().sorted(),
            ),
        )
        AnalysesTable.update({ AnalysesTable.id eq id }) {
            it[AnalysesTable.resultJson] = payload
            it[AnalysesTable.updatedAt] = OffsetDateTime.now()
        }
        true
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

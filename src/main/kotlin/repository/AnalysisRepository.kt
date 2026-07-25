package com.deviante.repository

import com.deviante.db.EventLogsTable
import com.deviante.db.TracesTable
import com.deviante.dto.AnalysisTracePointResponse
import com.deviante.model.EventLogRecord
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class AnalysisSeries(
    val eventLog: EventLogRecord,
    val points: List<AnalysisTracePointResponse>,
)

class AnalysisRepository {
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
}

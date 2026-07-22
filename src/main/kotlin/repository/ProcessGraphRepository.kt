package com.deviante.repository

import com.deviante.db.ActivitiesTable
import com.deviante.db.EventLogsTable
import com.deviante.db.OperationsTable
import com.deviante.db.TraceEventsTable
import com.deviante.db.TracesTable
import com.deviante.dto.GraphEdgeResponse
import com.deviante.dto.GraphNodeResponse
import com.deviante.dto.ProcessGraphResponse
import com.deviante.dto.TraceCaseResponse
import com.deviante.dto.TraceVariantResponse
import com.deviante.dto.TraceVariantsResponse
import com.deviante.dto.toResponse
import com.deviante.model.EventLogRecord
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max

/**
 * Turns what was ingested (UC4) into the directly-follows graph the canvas
 * draws (UC7) and the variant tree the traces panel lists.
 *
 * Deliberately derived on read rather than materialized: the graph changes the
 * moment a mapping is confirmed (UC5/UC6), and a stored copy would be a second
 * truth to keep in sync. The aggregation runs in Kotlin over the log's events —
 * fine at the scale of the real dataset (Prod1Torno.csv: 3.282 traces, 13.053
 * events); a log an order of magnitude larger is the point where this should
 * move into SQL window functions.
 *
 * A node is one **activity** once its operations are mapped, and one
 * **operation** while they are not: mapping is exactly the act of saying two
 * raw labels are the same real-world activity, so merging before the Manager
 * confirms would invent that claim.
 */
class ProcessGraphRepository {

    private companion object {
        const val START_ID = "__start__"
        const val END_ID = "__end__"
        const val HISTOGRAM_BUCKETS = 6
        /** Cases listed per variant in the panel; the rest stay behind the count. */
        const val CASES_PER_VARIANT = 8
    }

    /** One event of one trace, already resolved to the node it belongs to. */
    private data class Event(
        val traceId: UUID,
        val nodeId: String,
        val sequenceIndex: Int,
        val occurredAt: OffsetDateTime?,
        val traceEndedAt: OffsetDateTime?,
    )

    private data class NodeInfo(
        val id: String,
        val label: String,
        val rawLabel: String,
        val activityId: UUID?,
        val mappingStatus: String,
    )

    fun graph(processId: UUID): ProcessGraphResponse = transaction {
        val log = latestParsedLog(processId)
            ?: return@transaction ProcessGraphResponse(
                eventLog = null,
                caseCount = 0,
                eventCount = 0,
                hasUnmappedOperations = false,
                nodes = emptyList(),
                edges = emptyList(),
            )

        val nodesByOperation = nodesByOperation(log.id)
        val events = events(log.id, nodesByOperation)

        val byTrace = events.groupBy { it.traceId }
        val durations = mutableMapOf<String, MutableList<Double>>()
        val occurrences = mutableMapOf<String, Int>()
        val caseIds = mutableMapOf<String, MutableSet<UUID>>()
        val repetitions = mutableMapOf<String, Int>()
        val startFreq = mutableMapOf<String, Int>()
        val endFreq = mutableMapOf<String, Int>()
        val edgeCases = mutableMapOf<Pair<String, String>, Int>()

        for ((traceId, unordered) in byTrace) {
            val trace = unordered.sortedBy { it.sequenceIndex }
            if (trace.isEmpty()) continue

            val perTrace = mutableMapOf<String, Int>()
            trace.forEachIndexed { index, event ->
                occurrences.merge(event.nodeId, 1, Int::plus)
                perTrace.merge(event.nodeId, 1, Int::plus)
                caseIds.getOrPut(event.nodeId) { mutableSetOf() }.add(traceId)

                // Sojourn time — this event's start to the next one's, the same
                // quantity the mentor's ADWIN scripts stream (see
                // gestalt-kit/docs/architecture.md § Drift detection).
                val next = trace.getOrNull(index + 1)?.occurredAt ?: event.traceEndedAt
                val seconds = secondsBetween(event.occurredAt, next)
                if (seconds != null) durations.getOrPut(event.nodeId) { mutableListOf() }.add(seconds)

                if (index > 0) {
                    edgeCases.merge(trace[index - 1].nodeId to event.nodeId, 1, Int::plus)
                }
            }
            perTrace.forEach { (nodeId, count) ->
                repetitions.merge(nodeId, count) { old, new -> max(old, new) }
            }

            startFreq.merge(trace.first().nodeId, 1, Int::plus)
            endFreq.merge(trace.last().nodeId, 1, Int::plus)
            edgeCases.merge(START_ID to trace.first().nodeId, 1, Int::plus)
            edgeCases.merge(trace.last().nodeId to END_ID, 1, Int::plus)
        }

        val busiest = occurrences.values.maxOrNull() ?: 0
        val nodes = nodesByOperation.values.distinctBy { it.id }
            .filter { occurrences.containsKey(it.id) }
            .map { info ->
                val nodeDurations = durations[info.id].orEmpty().sorted()
                GraphNodeResponse(
                    id = info.id,
                    label = info.label,
                    rawLabel = info.rawLabel,
                    activityId = info.activityId?.toString(),
                    mappingStatus = info.mappingStatus,
                    frequency = if (busiest == 0) 0.0 else occurrences.getValue(info.id) * 100.0 / busiest,
                    absoluteFreq = occurrences.getValue(info.id),
                    caseFreq = caseIds[info.id]?.size ?: 0,
                    maxRepetitions = repetitions[info.id] ?: 1,
                    startFreq = startFreq[info.id] ?: 0,
                    endFreq = endFreq[info.id] ?: 0,
                    totalDurationSeconds = nodeDurations.sum(),
                    meanDurationSeconds = nodeDurations.average().orZero(),
                    medianDurationSeconds = nodeDurations.median(),
                    minDurationSeconds = nodeDurations.firstOrNull() ?: 0.0,
                    maxDurationSeconds = nodeDurations.lastOrNull() ?: 0.0,
                    histogram = histogram(nodeDurations),
                )
            }
            .sortedByDescending { it.absoluteFreq }

        val heaviestEdge = edgeCases.values.maxOrNull() ?: 0
        val edges = edgeCases.entries
            .sortedByDescending { it.value }
            .map { (pair, count) ->
                val (source, target) = pair
                GraphEdgeResponse(
                    id = "${source}__$target",
                    source = source,
                    target = target,
                    caseCount = count,
                    frequency = if (heaviestEdge == 0) 0.0 else count.toDouble() / heaviestEdge,
                    meanDurationSeconds = 0.0,
                )
            }

        ProcessGraphResponse(
            eventLog = log.toResponse(),
            caseCount = byTrace.size,
            eventCount = events.size,
            hasUnmappedOperations = nodesByOperation.values.any { it.mappingStatus == "unmapped" },
            nodes = nodes,
            edges = edges,
        )
    }

    fun variants(processId: UUID): TraceVariantsResponse = transaction {
        val log = latestParsedLog(processId)
            ?: return@transaction TraceVariantsResponse(0, 0, emptyList())

        val nodeIdByLabel = nodesByOperation(log.id).values.associate { it.rawLabel to it.id }

        val traces = TracesTable
            .selectAll()
            .where { TracesTable.eventLogId eq log.id }
            .map {
                TraceRow(
                    id = it[TracesTable.id],
                    caseId = it[TracesTable.caseId],
                    sequence = it[TracesTable.activitySequence],
                    startedAt = it[TracesTable.startedAt],
                    durationSeconds = it[TracesTable.durationSeconds]?.toDouble(),
                )
            }
        if (traces.isEmpty()) return@transaction TraceVariantsResponse(0, 0, emptyList())

        // p90 over the whole log: a case is "deviated" when it runs long
        // relative to everything else in the same log, not to a fixed target
        // nobody has defined yet (SLA/conformance is UC8, not this).
        val sortedDurations = traces.mapNotNull { it.durationSeconds }.sorted()
        val p90 = sortedDurations.percentile(0.90)

        val grouped = traces.groupBy { it.sequence }
            .entries
            .sortedByDescending { it.value.size }

        val dominant = grouped.firstOrNull()?.key

        val variants = grouped.mapIndexed { index, (sequence, group) ->
            val groupDurations = group.mapNotNull { it.durationSeconds }.sorted()
            TraceVariantResponse(
                id = "v${index + 1}",
                label = "Variante ${variantLetter(index)} — ${sequence.firstOrNull() ?: "vazia"} → ${sequence.lastOrNull() ?: "vazia"}",
                sequence = sequence,
                nodeIds = sequence.mapNotNull { nodeIdByLabel[it] },
                caseCount = group.size,
                deviation = sequence != dominant,
                medianDurationSeconds = groupDurations.median(),
                cases = group
                    .sortedByDescending { it.durationSeconds ?: 0.0 }
                    .take(CASES_PER_VARIANT)
                    .map { trace ->
                        TraceCaseResponse(
                            id = trace.id.toString(),
                            caseId = trace.caseId,
                            durationSeconds = trace.durationSeconds,
                            startedAt = trace.startedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                            status = if (p90 > 0 && (trace.durationSeconds ?: 0.0) > p90) "deviated" else "completed",
                        )
                    },
            )
        }

        TraceVariantsResponse(
            caseCount = traces.size,
            variantCount = variants.size,
            variants = variants,
        )
    }

    private data class TraceRow(
        val id: UUID,
        val caseId: String,
        val sequence: List<String>,
        val startedAt: OffsetDateTime?,
        val durationSeconds: Double?,
    )

    /** The log the canvas speaks for: the most recent one that parsed cleanly. */
    private fun latestParsedLog(processId: UUID): EventLogRecord? =
        EventLogsTable
            .selectAll()
            .where { (EventLogsTable.processId eq processId) and (EventLogsTable.parseStatus eq "parsed") }
            .orderBy(EventLogsTable.uploadedAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.toEventLogRecord()

    private fun nodesByOperation(eventLogId: UUID): Map<UUID, NodeInfo> =
        OperationsTable
            .join(ActivitiesTable, JoinType.LEFT, OperationsTable.activityId, ActivitiesTable.id)
            .selectAll()
            .where { OperationsTable.eventLogId eq eventLogId }
            .associate { row ->
                val operationId = row[OperationsTable.id]
                val activityId = row[OperationsTable.activityId]
                val rawLabel = row[OperationsTable.rawLabel]
                operationId to NodeInfo(
                    id = activityId?.let { "act:$it" } ?: "op:$operationId",
                    label = activityId?.let { row[ActivitiesTable.name] } ?: rawLabel,
                    rawLabel = rawLabel,
                    activityId = activityId,
                    mappingStatus = row[OperationsTable.mappingStatus],
                )
            }

    private fun events(eventLogId: UUID, nodes: Map<UUID, NodeInfo>): List<Event> =
        TraceEventsTable
            .join(TracesTable, JoinType.INNER, TraceEventsTable.traceId, TracesTable.id)
            .selectAll()
            .where { TracesTable.eventLogId eq eventLogId }
            .mapNotNull { row ->
                val node = nodes[row[TraceEventsTable.operationId]] ?: return@mapNotNull null
                Event(
                    traceId = row[TraceEventsTable.traceId],
                    nodeId = node.id,
                    sequenceIndex = row[TraceEventsTable.sequenceIndex],
                    occurredAt = row[TraceEventsTable.occurredAt],
                    traceEndedAt = row[TracesTable.endedAt],
                )
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

    private fun secondsBetween(from: OffsetDateTime?, to: OffsetDateTime?): Double? {
        if (from == null || to == null) return null
        val seconds = java.time.Duration.between(from, to).toMillis() / 1000.0
        return if (seconds < 0) null else seconds
    }

    private fun histogram(sorted: List<Double>): List<Double> {
        if (sorted.isEmpty()) return List(HISTOGRAM_BUCKETS) { 0.0 }
        val min = sorted.first()
        val max = sorted.last()
        if (max <= min) return List(HISTOGRAM_BUCKETS) { if (it == 0) 1.0 else 0.0 }

        val counts = IntArray(HISTOGRAM_BUCKETS)
        for (value in sorted) {
            val bucket = (((value - min) / (max - min)) * HISTOGRAM_BUCKETS).toInt()
            counts[bucket.coerceIn(0, HISTOGRAM_BUCKETS - 1)]++
        }
        val peak = counts.max()
        return counts.map { if (peak == 0) 0.0 else it.toDouble() / peak }
    }

    private fun List<Double>.median(): Double = percentile(0.50)

    /** Nearest-rank on an already-sorted list. */
    private fun List<Double>.percentile(fraction: Double): Double {
        if (isEmpty()) return 0.0
        val index = ((size - 1) * fraction).toInt().coerceIn(0, size - 1)
        return this[index]
    }

    private fun Double.orZero(): Double = if (isNaN()) 0.0 else this

    private fun variantLetter(index: Int): String =
        if (index < 26) ('A' + index).toString() else "${index + 1}"
}

package com.deviante.dto

import kotlinx.serialization.Serializable

/**
 * The process graph the canvas draws (UC7), derived from what was actually
 * ingested — never from a fixture. Every number here comes from
 * `deviante.traces` / `deviante.trace_events` of the process's latest parsed
 * event log; when the process has no parsed log, `eventLog` is null and both
 * lists are empty, and the canvas says so instead of drawing a demo graph.
 */
@Serializable
data class ProcessGraphResponse(
    val eventLog: EventLogResponse? = null,
    val eventLogs: List<EventLogResponse> = emptyList(),
    val caseCount: Int,
    val eventCount: Int,
    /** True while at least one operation is still `unmapped` (UC5 pending): the
     *  nodes are then labelled with the log's raw labels, not activity names. */
    val hasUnmappedOperations: Boolean,
    val nodes: List<GraphNodeResponse>,
    val edges: List<GraphEdgeResponse>,
)

@Serializable
data class GraphNodeResponse(
    /** Operation id — stable across reloads, so selection survives a refetch. */
    val id: String,
    val label: String,
    val rawLabel: String,
    val activityId: String? = null,
    val mappingStatus: String,
    /** Share of the busiest node's occurrences, 0–100 — drives the density slider. */
    val frequency: Double,
    val absoluteFreq: Int,
    val caseFreq: Int,
    val maxRepetitions: Int,
    val startFreq: Int,
    val endFreq: Int,
    /** Sojourn time: this event's timestamp to the next one in the same trace. */
    val totalDurationSeconds: Double,
    val meanDurationSeconds: Double,
    val medianDurationSeconds: Double,
    val minDurationSeconds: Double,
    val maxDurationSeconds: Double,
    /** 6 buckets over [min,max], normalized 0–1 — the detail panel histogram. */
    val histogram: List<Double>,
)

@Serializable
data class GraphEdgeResponse(
    val id: String,
    val source: String,
    val target: String,
    val caseCount: Int,
    /** Share of the busiest edge, 0–1 — drives thickness, opacity and the path slider. */
    val frequency: Double,
    val meanDurationSeconds: Double,
)

/**
 * Trace variants for the "Instâncias · Camadas" panel (the traces window in the
 * newer Figma export). A variant is a distinct `activity_sequence`; cases are a
 * capped sample of the real traces that follow it.
 */
@Serializable
data class TraceVariantsResponse(
    val caseCount: Int,
    val variantCount: Int,
    val variants: List<TraceVariantResponse>,
)

@Serializable
data class TraceVariantResponse(
    val id: String,
    val label: String,
    val sequence: List<String>,
    /** Operation ids of the sequence, in order — lets the panel filter by node. */
    val nodeIds: List<String>,
    val caseCount: Int,
    /** Anything other than the log's dominant variant is flagged as a deviation. */
    val deviation: Boolean,
    val medianDurationSeconds: Double,
    val cases: List<TraceCaseResponse>,
)

@Serializable
data class TraceCaseResponse(
    val id: String,
    val caseId: String,
    val durationSeconds: Double?,
    val startedAt: String? = null,
    /** `deviated` when the case runs past the log-wide p90 duration. */
    val status: String,
)

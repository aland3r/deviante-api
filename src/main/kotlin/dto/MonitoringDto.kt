package com.deviante.dto

import kotlinx.serialization.Serializable

/**
 * Request for the stateless monitoring drift-detection proxy.
 *
 * The monitoring registry (equipment, components, health series) is persisted
 * on the client in v1.0; only the detection is real. This carries a machine
 * parameter's numeric series straight to the IPDD/ADWIN service, with no
 * persistence on this side — the same research pipeline the process analysis
 * uses, so a health parameter is diagnosed exactly like a trace-duration one.
 */
@Serializable
data class MonitoringDetectRequest(
    val values: List<Double> = emptyList(),
    val delta: Double? = null,
    val treatment: String = "treated",
)

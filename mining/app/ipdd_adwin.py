"""IPDD/ADWIN adapter used by Deviante's online analysis.

This module operationalizes the ADWIN flow from Luiz Picolo's research
scripts in ``Adaptive-Detection-of-Performance-Related-Temporal-Drifts-main``.
Those scripts run **two different pipelines**, and Deviante has to be able to
reproduce both — otherwise its numbers cannot be checked against the ones
published for the research:

``TREATMENT_RAW`` mirrors ``adwin_dataset.py`` (synthetic manufacturing logs).
    The series goes into ADWIN untouched: no outlier replacement, no moving
    average. This is the mode that reproduces the baseline against the known
    injected drifts of the ``DR*`` / ``ST*`` corpus.

``TREATMENT_TREATED`` mirrors ``adwin_real_dataset.py`` (shop-floor CSV).
    Isolated performance spikes are replaced from their local neighbourhood
    and the series is smoothed by a centred moving average of
    ``min(15, max(3, n // 20))`` before ADWIN sees it.

The real-dataset script uses a fixed 60-second limit to recognize a spike,
calibrated for one specific machine. Deviante accepts arbitrary processes, so
the treated mode recognizes only *isolated local* spikes instead, a relative
criterion that does not depend on the magnitude of the process times.
Sustained level changes are preserved either way — they are the signal.
"""

from __future__ import annotations

import math
import statistics
from dataclasses import dataclass

from river.drift import ADWIN

TREATMENT_RAW = "raw"
TREATMENT_TREATED = "treated"
TREATMENTS = (TREATMENT_RAW, TREATMENT_TREATED)


@dataclass(frozen=True)
class AdaptiveDrift:
    detection_index: int
    anomaly_start_index: int
    value: float
    width: float
    estimation: float


@dataclass(frozen=True)
class AdaptiveDetection:
    processed_values: list[float]
    outlier_indices: list[int]
    smoothing_window: int
    treatment: str
    drifts: list[AdaptiveDrift]


def _is_isolated_spike(value: float, neighbours: list[float]) -> bool:
    """Return whether ``value`` is an isolated local spike."""
    if len(neighbours) < 2:
        return False

    centre = statistics.median(neighbours)
    deviations = [abs(item - centre) for item in neighbours]
    mad = statistics.median(deviations)
    distance = abs(value - centre)
    scale = max(abs(centre) * 0.05, 1e-9)

    if mad <= scale:
        return distance > max(scale * 6, 1e-9)

    robust_sigma = 1.4826 * mad
    return distance > 3.5 * robust_sigma


def replace_isolated_outliers(
    values: list[float],
    neighbour_window: int = 2,
) -> tuple[list[float], list[int]]:
    """Treat isolated spikes while preserving sustained level changes."""
    original = [float(value) for value in values]
    treated = original.copy()
    outliers: list[int] = []

    for index, value in enumerate(original):
        neighbours = [
            original[position]
            for position in range(
                max(0, index - neighbour_window),
                min(len(original), index + neighbour_window + 1),
            )
            if position != index
        ]
        if _is_isolated_spike(value, neighbours):
            treated[index] = sum(neighbours) / len(neighbours)
            outliers.append(index)

    return treated, outliers


def smoothing_window(observation_count: int) -> int:
    """Window rule preserved from Luiz's real-dataset implementation."""
    return min(15, max(3, observation_count // 20))


def centered_moving_average(values: list[float], window: int) -> list[float]:
    """Equivalent to pandas' centered rolling mean with ``min_periods=1``."""
    left = (window - 1) // 2
    right = window // 2
    averages: list[float] = []

    for index in range(len(values)):
        sample = values[
            max(0, index - left) : min(len(values), index + right + 1)
        ]
        averages.append(sum(sample) / len(sample))

    return averages


def estimate_change_start(
    values: list[float],
    detection_index: int,
    adaptive_width: int,
    segment_start: int = 0,
) -> int:
    """Locate the strongest two-regime split preceding an ADWIN detection."""
    lookback = max(adaptive_width * 4, 64)
    start = max(segment_start, detection_index - lookback + 1)
    sample = values[start : detection_index + 1]
    minimum_side = min(5, max(2, len(sample) // 4))
    if len(sample) < minimum_side * 2:
        return max(segment_start, detection_index - adaptive_width + 1)

    prefix = [0.0]
    prefix_squares = [0.0]
    for value in sample:
        prefix.append(prefix[-1] + value)
        prefix_squares.append(prefix_squares[-1] + value * value)

    def squared_error(left: int, right: int) -> float:
        count = right - left
        total = prefix[right] - prefix[left]
        squares = prefix_squares[right] - prefix_squares[left]
        return max(0.0, squares - total * total / count)

    candidates = range(minimum_side, len(sample) - minimum_side + 1)
    split = min(
        candidates,
        key=lambda position: squared_error(0, position)
        + squared_error(position, len(sample)),
    )
    return start + split


def detect_performance_drifts(
    values: list[float],
    *,
    delta: float = 0.002,
    treatment: str = TREATMENT_TREATED,
) -> AdaptiveDetection:
    if len(values) < 2:
        raise ValueError("A análise exige ao menos duas observações.")
    if any(not math.isfinite(value) for value in values):
        raise ValueError("A série contém valores numéricos inválidos.")
    if treatment not in TREATMENTS:
        raise ValueError(
            f"Tratamento desconhecido: {treatment}. Use 'raw' ou 'treated'."
        )

    if treatment == TREATMENT_RAW:
        # adwin_dataset.py feeds ADWIN the series as read from the log. A
        # window of 1 is reported so the caller can tell "no smoothing" apart
        # from "smoothing that happened to pick a width of 1".
        processed = [float(value) for value in values]
        outliers: list[int] = []
        window = 1
    else:
        treated, outliers = replace_isolated_outliers(values)
        window = smoothing_window(len(treated))
        processed = centered_moving_average(treated, window)

    detector = ADWIN(delta=delta)
    drifts: list[AdaptiveDrift] = []
    segment_start = 0

    for index, value in enumerate(processed):
        detector.update(value)
        if detector.drift_detected:
            anomaly_start = estimate_change_start(
                processed,
                index,
                int(detector.width),
                segment_start,
            )
            drifts.append(
                AdaptiveDrift(
                    detection_index=index,
                    anomaly_start_index=anomaly_start,
                    value=value,
                    width=float(detector.width),
                    estimation=float(detector.estimation),
                )
            )
            segment_start = index + 1

    return AdaptiveDetection(
        processed_values=processed,
        outlier_indices=outliers,
        smoothing_window=window,
        treatment=treatment,
        drifts=drifts,
    )

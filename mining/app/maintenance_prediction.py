"""Thin, leakage-safe adapter around Luiz Picolo's RF experiments.

The research repository contains training code, but no serialized production
model.  This adapter therefore fits the same Random Forest families from
labelled runs supplied by the caller and predicts one target history in the
same stateless request.  It deliberately excludes the synthetic ground-truth
drift (``DriftR``), absolute trace number, and maintenance occurrence.
"""

from __future__ import annotations

import math
import statistics
from dataclasses import dataclass

FAILURE_HORIZON_TRACES = 20
RUL_WINDOW_TRACES = 30
MODEL_VERSION = "luiz-rf-leakage-safe-v1"
TREE_COUNT = 200
SIGNALS = ("machine_operating", "raw_material_loading", "short_downtime")


class PredictionInputError(ValueError):
    """The supplied histories cannot support an honest prediction."""


@dataclass(frozen=True)
class Observation:
    machine_operating: float
    raw_material_loading: float
    short_downtime: float
    drift_detected: bool = False
    failure_occurred: bool | None = None


@dataclass(frozen=True)
class MaintenancePrediction:
    failure_probability: float
    failure_horizon_traces: int
    rul_traces: float
    model_version: str
    training_run_count: int
    training_observation_count: int
    labelled_failure_count: int


def _validate_run(run: list[Observation], *, labelled: bool) -> None:
    minimum = max(RUL_WINDOW_TRACES, FAILURE_HORIZON_TRACES + 1)
    if len(run) < minimum:
        raise PredictionInputError(
            f"Cada serie precisa de ao menos {minimum} traces ordenados."
        )
    for observation in run:
        values = (getattr(observation, signal) for signal in SIGNALS)
        if any(not math.isfinite(value) for value in values):
            raise PredictionInputError("A serie contem duracoes invalidas.")
        if labelled and observation.failure_occurred is None:
            raise PredictionInputError(
                "Todos os traces de treinamento precisam do rotulo failureOccurred."
            )


def _sample_std(values: list[float]) -> float:
    return statistics.stdev(values) if len(values) > 1 else 0.0


def _failure_features(run: list[Observation], index: int) -> list[float]:
    """Mirror prediction_test.py's causal features, minus DriftR."""
    features: list[float] = []
    for signal in SIGNALS:
        values = [float(getattr(item, signal)) for item in run]
        current = values[index]
        last5 = values[max(0, index - 4) : index + 1]
        last10 = values[max(0, index - 9) : index + 1]
        features.extend(
            [
                current,
                statistics.fmean(last5),
                statistics.fmean(last10),
                _sample_std(last5),
                _sample_std(last10),
                max(last5),
                min(last5),
                max(last5) - min(last5),
                current - values[index - 1] if index >= 1 else 0.0,
                current - values[index - 5] if index >= 5 else 0.0,
            ]
        )
    features.append(float(run[index].drift_detected))
    return features


def _rul_features(run: list[Observation], index: int) -> list[float]:
    """Mirror prediction_final.py's 30-trace window, minus absolute Trace."""
    features: list[float] = []
    for signal in (*SIGNALS, "drift_detected"):
        features.extend(
            float(getattr(run[index - lag], signal))
            for lag in range(RUL_WINDOW_TRACES)
        )
    return features


def _next_failure(run: list[Observation], index: int) -> int | None:
    for position in range(index, len(run)):
        if run[position].failure_occurred:
            return position
    return None


def _new_failure_model():
    from sklearn.ensemble import RandomForestClassifier

    return RandomForestClassifier(
        n_estimators=TREE_COUNT,
        min_samples_leaf=2,
        class_weight="balanced_subsample",
        random_state=42,
        n_jobs=1,
    )


def _new_rul_model():
    from sklearn.ensemble import RandomForestRegressor

    return RandomForestRegressor(
        n_estimators=TREE_COUNT,
        random_state=42,
        n_jobs=1,
    )


def predict_maintenance(
    training_runs: list[list[Observation]],
    history: list[Observation],
) -> MaintenancePrediction:
    """Fit Luiz's RF families on labelled runs and predict the latest trace."""
    if not training_runs:
        raise PredictionInputError(
            "Informe ao menos uma serie historica rotulada; o servico nao inventa labels."
        )
    for run in training_runs:
        _validate_run(run, labelled=True)
    _validate_run(history, labelled=False)

    labelled_failures = sum(
        item.failure_occurred is True for run in training_runs for item in run
    )
    if labelled_failures == 0:
        raise PredictionInputError(
            "As series de treinamento nao contem eventos de falha rotulados."
        )

    failure_x: list[list[float]] = []
    failure_y: list[int] = []
    rul_x: list[list[float]] = []
    rul_y: list[float] = []

    for run in training_runs:
        # The last 20 rows are right-censored for the classifier: the complete
        # future horizon is unknown, so they must not be labelled negative.
        for index in range(0, len(run) - FAILURE_HORIZON_TRACES):
            future = run[index + 1 : index + FAILURE_HORIZON_TRACES + 1]
            failure_x.append(_failure_features(run, index))
            failure_y.append(int(any(item.failure_occurred for item in future)))

        # After the final observed failure, RUL is censored.  Unlike the
        # research prototype, do not append a virtual failure at end-of-file.
        for index in range(RUL_WINDOW_TRACES - 1, len(run)):
            failure_index = _next_failure(run, index)
            if failure_index is None:
                continue
            rul_x.append(_rul_features(run, index))
            rul_y.append(float(failure_index - index))

    if set(failure_y) != {0, 1}:
        raise PredictionInputError(
            "Os dados rotulados precisam conter janelas positivas e negativas."
        )
    if not rul_y:
        raise PredictionInputError(
            "Nao ha janela historica completa antes de uma falha rotulada."
        )

    failure_model = _new_failure_model()
    failure_model.fit(failure_x, failure_y)
    positive_index = list(failure_model.classes_).index(1)
    failure_probability = float(
        failure_model.predict_proba([_failure_features(history, len(history) - 1)])[0][
            positive_index
        ]
    )

    rul_model = _new_rul_model()
    rul_model.fit(rul_x, rul_y)
    rul_traces = max(
        0.0,
        float(rul_model.predict([_rul_features(history, len(history) - 1)])[0]),
    )

    return MaintenancePrediction(
        failure_probability=failure_probability,
        failure_horizon_traces=FAILURE_HORIZON_TRACES,
        rul_traces=rul_traces,
        model_version=MODEL_VERSION,
        training_run_count=len(training_runs),
        training_observation_count=sum(map(len, training_runs)),
        labelled_failure_count=labelled_failures,
    )

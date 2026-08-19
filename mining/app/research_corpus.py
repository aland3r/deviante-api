"""Load the labelled maintenance corpus bundled with Luiz Picolo's work."""

from __future__ import annotations

import csv
from functools import lru_cache
from pathlib import Path

from .maintenance_prediction import Observation, PredictionInputError

_DATASETS = ("DR_MS_04_matrix.csv", "DR_MS_ST_25_matrix.csv")


def _matrix_dir() -> Path:
    return (
        Path(__file__).resolve().parents[2]
        / "Adaptive-Detection-of-Performance-Related-Temporal-Drifts-main"
        / "prediction"
        / "matrix_log"
    )


@lru_cache(maxsize=1)
def load_research_training_runs() -> tuple[tuple[Observation, ...], ...]:
    runs: list[tuple[Observation, ...]] = []
    for file_name in _DATASETS:
        path = _matrix_dir() / file_name
        if not path.is_file():
            raise PredictionInputError(f"Corpus de treinamento ausente: {file_name}.")
        observations: list[Observation] = []
        with path.open(encoding="utf-8-sig", newline="") as stream:
            for row in csv.DictReader(stream, delimiter=";"):
                observations.append(
                    Observation(
                        machine_operating=float(row["Machine_Operating"]),
                        raw_material_loading=float(row["Raw_Material_Loading"]),
                        short_downtime=float(row["Short_Downtime"]),
                        drift_detected=row["DriftD"] == "1",
                        failure_occurred=row["Equipment_Failure"] == "1",
                    )
                )
        runs.append(tuple(observations))
    return tuple(runs)


def research_training_runs() -> list[list[Observation]]:
    """Return copies so a request cannot mutate the cached corpus."""
    return [list(run) for run in load_research_training_runs()]


def research_dataset_names() -> list[str]:
    return list(_DATASETS)

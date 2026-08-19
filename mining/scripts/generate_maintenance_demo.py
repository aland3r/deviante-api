"""Generate the deterministic database seed from Luiz Picolo's datasets."""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import defaultdict
from dataclasses import asdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

MINING_ROOT = Path(__file__).resolve().parents[1]
if str(MINING_ROOT) not in sys.path:
    sys.path.insert(0, str(MINING_ROOT))

from app.ipdd_adwin import detect_performance_drifts
from app.maintenance_prediction import Observation, predict_maintenance
from app.research_corpus import research_dataset_names, research_training_runs

ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "Adaptive-Detection-of-Performance-Related-Temporal-Drifts-main"


def readings(rows: list[Observation]) -> dict[str, list[dict[str, object]]]:
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    result: dict[str, list[dict[str, object]]] = {}
    for field, name in (
        ("machine_operating", "Machine_Operating"),
        ("raw_material_loading", "Raw_Material_Loading"),
        ("short_downtime", "Short_Downtime"),
    ):
        result[name] = [
            {"observedAt": (start + timedelta(minutes=i)).isoformat(), "value": getattr(row, field)}
            for i, row in enumerate(rows)
        ]
    return result


def synthetic(name: str, run: list[Observation]) -> dict[str, object]:
    history = [
        Observation(
            item.machine_operating,
            item.raw_material_loading,
            item.short_downtime,
            item.drift_detected,
            None,
        )
        for item in run[-80:]
    ]
    prediction = predict_maintenance(research_training_runs(), history)
    detection = detect_performance_drifts(
        [item.machine_operating for item in history], delta=0.002, treatment="treated"
    )
    recommendation = (
        "Priorizar inspeção e planejar manutenção antes do RUL estimado."
        if prediction.failure_probability >= 0.70 or prediction.rul_traces <= 10
        else "Agendar inspeção preventiva e acompanhar a próxima janela."
        if prediction.failure_probability >= 0.40 or prediction.rul_traces <= 20
        else "Manter monitoramento; não há intervenção imediata indicada."
    )
    return {
        "name": name,
        "kind": "esteira",
        "tag": name.replace("_matrix.csv", ""),
        "dataset": f"prediction/matrix_log/{name}",
        "readings": readings(history),
        "analysis": {
            "parameterName": "Machine_Operating",
            "method": "IPDD + ADWIN",
            "delta": 0.002,
            "treatment": detection.treatment,
            "processedValues": detection.processed_values,
            "outlierIndices": detection.outlier_indices,
            "drifts": [asdict(item) for item in detection.drifts],
            "rulValue": prediction.rul_traces,
            "rulUnit": "traces",
            "failureProbability": prediction.failure_probability,
            "failureHorizonValue": prediction.failure_horizon_traces,
            "failureHorizonUnit": "traces",
            "modelVersion": prediction.model_version,
            "recommendation": recommendation,
            "provenance": {
                "source": "Luiz Picolo research Random Forest adapter; bundled labelled corpus",
                "trainingDatasets": research_dataset_names(),
                "excludedFeatures": ["DriftR", "Trace", "Maintenance"],
                "trainingRunCount": prediction.training_run_count,
                "trainingObservationCount": prediction.training_observation_count,
                "labelledFailureCount": prediction.labelled_failure_count,
            },
        },
    }


def real_torno() -> dict[str, object]:
    source = RESEARCH / "real_dataset" / "Prod1Torno.csv"
    cases: dict[str, dict[str, float]] = defaultdict(lambda: defaultdict(float))
    with source.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream, delimiter=";"):
            cases[row["Case"]][row["Atividade"]] += float(row["Tempo(s)"])
    activity_map = {
        "Maquina trabalhando": "Machine_Operating",
        "Alimentacao de Maquina": "Raw_Material_Loading",
        "Parada de curta duracao": "Short_Downtime",
    }
    start = datetime(2026, 2, 1, tzinfo=timezone.utc)
    series = {name: [] for name in activity_map.values()}
    for index, case in enumerate(sorted(cases, key=lambda value: int(value))[-80:]):
        for source_name, target_name in activity_map.items():
            series[target_name].append({
                "observedAt": (start + timedelta(minutes=index)).isoformat(),
                "value": cases[case].get(source_name, 0.0),
            })
    values = [row["value"] for row in series["Machine_Operating"]]
    detection = detect_performance_drifts(values, delta=0.002, treatment="treated")
    return {
        "name": "Prod1 Torno",
        "kind": "torno",
        "tag": "PROD1-TORNO",
        "dataset": "real_dataset/Prod1Torno.csv",
        "readings": series,
        "analysis": {
            "parameterName": "Machine_Operating",
            "method": "IPDD + ADWIN",
            "delta": 0.002,
            "treatment": detection.treatment,
            "processedValues": detection.processed_values,
            "outlierIndices": detection.outlier_indices,
            "drifts": [asdict(item) for item in detection.drifts],
            "rulValue": None,
            "rulUnit": "traces",
            "failureProbability": None,
            "failureHorizonValue": 20,
            "failureHorizonUnit": "traces",
            "modelVersion": None,
            "recommendation": "Previsão indisponível: o dataset real não contém rótulo de falha confiável para validação.",
            "provenance": {
                "source": "Prod1Torno real; somente detecção IPDD/ADWIN",
                "unavailableReason": "no_reliable_failure_label",
            },
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    training = research_training_runs()
    payload = {
        "version": 1,
        "monitoring": "Luiz Picolo · Cenário de manutenção",
        "equipment": [
            synthetic("DR_MS_04_matrix.csv", training[0]),
            synthetic("DR_MS_ST_25_matrix.csv", training[1]),
            real_torno(),
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "output": str(args.output),
        "equipment": len(payload["equipment"]),
        "predictions": sum(item["analysis"]["rulValue"] is not None for item in payload["equipment"]),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()

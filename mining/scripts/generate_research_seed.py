"""Generate a small, versioned JSON payload from Luiz's research datasets.

The output is an interchange artifact for a backend seed.  This script never
connects to or writes a database, and never fabricates failure labels for the
real turning-machine dataset.
"""

from __future__ import annotations

import csv
import json
from pathlib import Path
import xml.etree.ElementTree as ET

MINING_ROOT = Path(__file__).resolve().parents[1]
DEVIANTE_ROOT = MINING_ROOT.parent
MANIFEST_PATH = MINING_ROOT / "research-seed" / "manifest.json"

ACTIVITY_FIELDS = {
    "Machine_Operating": "machineOperating",
    "Raw_Material_Loading": "rawMaterialLoading",
    "Short_Downtime": "shortDowntime",
}


def _attribute(elements, key: str):
    return next((item.attrib.get("value") for item in elements if item.attrib.get("key") == key), None)


def read_xes(path: Path, limit: int) -> list[dict]:
    observations: list[dict] = []
    root = ET.parse(path).getroot()
    traces = [item for item in root if item.tag.endswith("trace")][:limit]
    for trace in traces:
        values = {field: 0.0 for field in ACTIVITY_FIELDS.values()}
        failure = False
        for event in (item for item in trace if item.tag.endswith("event")):
            children = list(event)
            activity = _attribute(children, "Activity") or _attribute(children, "concept:name")
            lifecycle = _attribute(children, "lifecycle:transition")
            if activity == "Equipment_Failure":
                failure = True
            if activity in ACTIVITY_FIELDS and lifecycle in (None, "complete"):
                duration = float(_attribute(children, "Duration") or 0)
                values[ACTIVITY_FIELDS[activity]] += duration
        observations.append(
            {
                **values,
                "driftDetected": False,
                "failureOccurred": failure,
            }
        )
    return observations


def read_real_csv(path: Path, limit: int) -> list[dict]:
    by_case: dict[str, dict] = {}
    with path.open(encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle, delimiter=";"):
            case = row["Case"]
            if case not in by_case and len(by_case) >= limit:
                continue
            item = by_case.setdefault(
                case,
                {
                    "caseId": case,
                    "machineOperating": 0.0,
                    "rawMaterialLoading": 0.0,
                    "shortDowntime": 0.0,
                },
            )
            field = {
                "Maquina trabalhando": "machineOperating",
                "Alimentacao de Maquina": "rawMaterialLoading",
                "Parada de curta duracao": "shortDowntime",
            }.get(row["Atividade"])
            if field:
                item[field] += float(row["Tempo(s)"])
    # Deliberately no failureOccurred key: this corpus is drift-only.
    return list(by_case.values())


def main() -> None:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    labelled_runs = []
    provenance = []
    drift_only = None
    target_history = None
    for source in manifest["sources"]:
        path = DEVIANTE_ROOT / source["path"]
        provenance.append({**source, "path": path.relative_to(DEVIANTE_ROOT).as_posix()})
        if source["role"] == "drift-only":
            drift_only = {
                "source": path.name,
                "failureLabelsAvailable": False,
                "observations": read_real_csv(path, source["maxTraces"]),
            }
            continue
        run = read_xes(path, source["maxTraces"])
        labelled_runs.append(run)
        if "target-history" in source["role"]:
            target_history = [
                {key: value for key, value in item.items() if key != "failureOccurred"}
                for item in run
            ]

    payload = {
        "manifestVersion": manifest["version"],
        "provenance": provenance,
        "predictionPayload": {
            "trainingRuns": labelled_runs,
            "history": target_history,
        },
        "driftOnly": drift_only,
    }
    output = MINING_ROOT / "research-seed" / manifest["output"]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()

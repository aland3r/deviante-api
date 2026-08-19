"""Deviante process-mining service.

Stateless compute called by the Kotlin API — never by the browser, and it
holds no database connection. See gestalt-kit/docs/architecture.md
§ Process mining exception: Kotlin owns persistence end to end.

It exposes event-log parsing (UC4) and a thin ADWIN adapter for drift
detection. Kotlin remains responsible for authorization and persistence.
"""

from __future__ import annotations

from typing import Literal

from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from .ipdd_adwin import TREATMENT_TREATED, detect_performance_drifts
from .maintenance_prediction import (
    FAILURE_HORIZON_TRACES,
    RUL_WINDOW_TRACES,
    MODEL_VERSION,
    Observation,
    PredictionInputError,
    predict_maintenance,
)
from .research_corpus import research_dataset_names, research_training_runs

app = FastAPI(
    title="Deviante mining service",
    version="0.1.0",
    description="Event-log parsing and drift detection for Deviante. Stateless.",
)

# Guards the Kotlin API against a pathological upload holding the worker.
MAX_UPLOAD_BYTES = 64 * 1024 * 1024


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


class DetectRequest(BaseModel):
    values: list[float]
    delta: float = Field(default=0.002, gt=0, lt=1)
    # "treated" keeps the historical behaviour of this endpoint, so an older
    # caller that omits the field gets exactly what it used to get.
    treatment: Literal["raw", "treated"] = TREATMENT_TREATED


class DriftPoint(BaseModel):
    index: int
    anomaly_start_index: int
    value: float
    width: float
    estimation: float


class DetectResponse(BaseModel):
    method: str
    delta: float
    treatment: str
    observation_count: int
    smoothing_window: int
    processed_values: list[float]
    outlier_indices: list[int]
    drifts: list[DriftPoint]


class PredictionObservationRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    machine_operating: float = Field(alias="machineOperating")
    raw_material_loading: float = Field(alias="rawMaterialLoading")
    short_downtime: float = Field(alias="shortDowntime")
    drift_detected: bool = Field(default=False, alias="driftDetected")
    failure_occurred: bool | None = Field(default=None, alias="failureOccurred")

    def to_domain(self) -> Observation:
        return Observation(
            machine_operating=self.machine_operating,
            raw_material_loading=self.raw_material_loading,
            short_downtime=self.short_downtime,
            drift_detected=self.drift_detected,
            failure_occurred=self.failure_occurred,
        )


class MaintenancePredictionRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    training_runs: list[list[PredictionObservationRequest]] = Field(
        default_factory=list, alias="trainingRuns"
    )
    history: list[PredictionObservationRequest]


class PredictionProvenance(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    source: str
    model_version: str = Field(alias="modelVersion")
    failure_model: str = Field(alias="failureModel")
    rul_model: str = Field(alias="rulModel")
    failure_horizon_traces: int = Field(alias="failureHorizonTraces")
    rul_window_traces: int = Field(alias="rulWindowTraces")
    training_run_count: int = Field(alias="trainingRunCount")
    training_observation_count: int = Field(alias="trainingObservationCount")
    labelled_failure_count: int = Field(alias="labelledFailureCount")
    excluded_features: list[str] = Field(alias="excludedFeatures")
    training_datasets: list[str] = Field(default_factory=list, alias="trainingDatasets")


class MaintenancePredictionResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    failure_probability: float = Field(alias="failureProbability")
    failure_horizon_traces: int = Field(alias="failureHorizonTraces")
    rul_traces: float = Field(alias="rulTraces")
    rul_unit: Literal["traces"] = Field(default="traces", alias="rulUnit")
    provenance: PredictionProvenance


@app.post("/detect", response_model=DetectResponse)
def detect(request: DetectRequest) -> DetectResponse:
    """Run Luiz Picolo's IPDD/ADWIN performance-analysis flow.

    ``treatment`` selects which of the two research pipelines to reproduce:
    ``raw`` for the synthetic-corpus baseline (``adwin_dataset.py``) and
    ``treated`` for the shop-floor one (``adwin_real_dataset.py``).
    """
    try:
        result = detect_performance_drifts(
            request.values,
            delta=request.delta,
            treatment=request.treatment,
        )
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error

    return DetectResponse(
        method="IPDD + ADWIN",
        delta=request.delta,
        treatment=result.treatment,
        observation_count=len(request.values),
        smoothing_window=result.smoothing_window,
        processed_values=result.processed_values,
        outlier_indices=result.outlier_indices,
        drifts=[
            DriftPoint(
                index=drift.detection_index,
                anomaly_start_index=drift.anomaly_start_index,
                value=drift.value,
                width=drift.width,
                estimation=drift.estimation,
            )
            for drift in result.drifts
        ],
    )


@app.post(
    "/predict-maintenance",
    response_model=MaintenancePredictionResponse,
    response_model_by_alias=True,
)
def predict_maintenance_endpoint(
    request: MaintenancePredictionRequest,
) -> MaintenancePredictionResponse:
    """Estimate 20-trace failure risk and RUL in traces.

    If the caller omits training runs, the service uses the two labelled trace
    matrices generated by Luiz's research pipeline and bundled in the repo.
    """
    try:
        supplied_runs = [
                [observation.to_domain() for observation in run]
                for run in request.training_runs
            ]
        training_runs = supplied_runs or research_training_runs()
        prediction = predict_maintenance(
            training_runs,
            [observation.to_domain() for observation in request.history],
        )
    except PredictionInputError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error

    return MaintenancePredictionResponse(
        failureProbability=prediction.failure_probability,
        failureHorizonTraces=prediction.failure_horizon_traces,
        rulTraces=prediction.rul_traces,
        provenance=PredictionProvenance(
            source=(
                "Luiz Picolo research Random Forest adapter; bundled labelled corpus"
                if not supplied_runs
                else "Luiz Picolo research Random Forest adapter; caller-supplied corpus"
            ),
            modelVersion=MODEL_VERSION,
            failureModel="RandomForestClassifier",
            rulModel="RandomForestRegressor",
            failureHorizonTraces=FAILURE_HORIZON_TRACES,
            rulWindowTraces=RUL_WINDOW_TRACES,
            trainingRunCount=prediction.training_run_count,
            trainingObservationCount=prediction.training_observation_count,
            labelledFailureCount=prediction.labelled_failure_count,
            excludedFeatures=["DriftR", "Trace", "Maintenance"],
            trainingDatasets=(research_dataset_names() if not supplied_runs else []),
        ),
    )


@app.post("/parse")
async def parse(file: UploadFile = File(...)) -> JSONResponse:
    """Parse an XES/CSV event log into distinct labels + ordered traces.

    Returns the shape the Kotlin API persists into `deviante.event_logs`,
    `deviante.operations`, `deviante.traces` and `deviante.trace_events`.
    """
    # PM4Py is intentionally imported only for parsing. Prediction and health
    # requests should not pay its substantial import/startup cost.
    from .parser import ParseError, parse_log

    content = await file.read()

    if not content:
        raise HTTPException(status_code=422, detail="Arquivo vazio.")
    if len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"Arquivo acima do limite de {MAX_UPLOAD_BYTES // (1024 * 1024)} MB.",
        )

    try:
        result = parse_log(content, file.filename or "")
    except ParseError as err:
        raise HTTPException(status_code=422, detail=str(err)) from err

    return JSONResponse(
        {
            "fileName": file.filename,
            "format": result.format,
            "operationCount": result.operation_count,
            "traceCount": result.trace_count,
            "events": [
                {
                    "rawLabel": event.raw_label,
                    "occurrenceCount": event.occurrence_count,
                    "caseCount": event.case_count,
                    "totalDurationSeconds": event.total_duration_seconds,
                    "meanDurationSeconds": event.mean_duration_seconds,
                }
                for event in result.events
            ],
            "traces": [
                {
                    "caseId": trace.case_id,
                    "eventCount": trace.event_count,
                    "startedAt": _iso(trace.started_at),
                    "endedAt": _iso(trace.ended_at),
                    "durationSeconds": trace.duration_seconds,
                    "events": [
                        {
                            "rawLabel": event.raw_label,
                            "sequenceIndex": event.sequence_index,
                            "occurredAt": _iso(event.occurred_at),
                            "durationSeconds": event.duration_seconds,
                        }
                        for event in trace.events
                    ],
                }
                for trace in result.traces
            ],
        }
    )


def _iso(value) -> str | None:
    return value.isoformat() if value is not None else None

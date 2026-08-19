import pytest
from fastapi import HTTPException

from app import maintenance_prediction as prediction
from app.main import (
    MaintenancePredictionRequest,
    PredictionObservationRequest,
    predict_maintenance_endpoint,
)
from app.research_corpus import research_training_runs


class FakeClassifier:
    classes_ = [0, 1]

    def fit(self, features, targets):
        assert features and set(targets) == {0, 1}
        return self

    def predict_proba(self, features):
        assert len(features[0]) == 31  # 3 causal signal groups + DriftD
        return [[0.27, 0.73]]


class FakeRegressor:
    def fit(self, features, targets):
        assert features and min(targets) >= 0
        return self

    def predict(self, features):
        assert len(features[0]) == 120  # 4 causal signals x 30 lags
        return [12.5]


def make_run(count=80, failure_at=50, labelled=True):
    return [
        prediction.Observation(
            machine_operating=10.0 + index / 10,
            raw_material_loading=8.0,
            short_downtime=2.0,
            drift_detected=index >= 35,
            failure_occurred=(index == failure_at) if labelled else None,
        )
        for index in range(count)
    ]


def test_probability_rul_and_provenance_are_bounded(monkeypatch):
    monkeypatch.setattr(prediction, "_new_failure_model", FakeClassifier)
    monkeypatch.setattr(prediction, "_new_rul_model", FakeRegressor)

    result = prediction.predict_maintenance([make_run()], make_run(labelled=False))

    assert 0 <= result.failure_probability <= 1
    assert result.failure_horizon_traces == 20
    assert result.rul_traces >= 0
    assert result.model_version == "luiz-rf-leakage-safe-v1"


def test_rejects_training_data_without_labels():
    with pytest.raises(prediction.PredictionInputError, match="failureOccurred"):
        prediction.predict_maintenance(
            [make_run(labelled=False)], make_run(labelled=False)
        )


def test_feature_contract_excludes_known_leakage():
    fields = prediction.Observation.__dataclass_fields__
    assert "trace" not in fields
    assert "drift_r" not in fields
    assert "maintenance" not in fields
    assert len(prediction._failure_features(make_run(), 40)) == 31
    assert len(prediction._rul_features(make_run(), 40)) == 120


def test_endpoint_uses_camel_case_contract(monkeypatch):
    monkeypatch.setattr(prediction, "_new_failure_model", FakeClassifier)
    monkeypatch.setattr(prediction, "_new_rul_model", FakeRegressor)

    def request_observation(item):
        return PredictionObservationRequest(
            machineOperating=item.machine_operating,
            rawMaterialLoading=item.raw_material_loading,
            shortDowntime=item.short_downtime,
            driftDetected=item.drift_detected,
            failureOccurred=item.failure_occurred,
        )

    request = MaintenancePredictionRequest(
        trainingRuns=[[request_observation(item) for item in make_run()]],
        history=[request_observation(item) for item in make_run(labelled=False)],
    )
    body = predict_maintenance_endpoint(request).model_dump(by_alias=True)

    assert body["failureProbability"] == 0.73
    assert body["rulTraces"] == 12.5
    assert body["provenance"]["excludedFeatures"] == [
        "DriftR",
        "Trace",
        "Maintenance",
    ]


def test_bundled_luiz_corpus_is_used_when_training_runs_are_omitted(monkeypatch):
    monkeypatch.setattr(prediction, "_new_failure_model", FakeClassifier)
    monkeypatch.setattr(prediction, "_new_rul_model", FakeRegressor)
    history = [
        PredictionObservationRequest(
            machineOperating=item.machine_operating,
            rawMaterialLoading=item.raw_material_loading,
            shortDowntime=item.short_downtime,
            driftDetected=item.drift_detected,
        )
        for item in make_run(labelled=False)
    ]

    body = predict_maintenance_endpoint(
        MaintenancePredictionRequest(history=history)
    ).model_dump(by_alias=True)

    assert body["failureProbability"] == 0.73
    assert body["provenance"]["trainingDatasets"] == [
        "DR_MS_04_matrix.csv",
        "DR_MS_ST_25_matrix.csv",
    ]


def test_bundled_luiz_corpus_contains_real_failure_labels():
    runs = research_training_runs()
    assert len(runs) == 2
    assert all(len(run) >= 30 for run in runs)
    assert all(any(item.failure_occurred for item in run) for run in runs)


def test_endpoint_returns_422_for_unlabelled_research_data():
    observation = PredictionObservationRequest(
        machineOperating=10,
        rawMaterialLoading=8,
        shortDowntime=2,
    )
    request = MaintenancePredictionRequest(
        trainingRuns=[[observation] * 30],
        history=[observation] * 30,
    )

    with pytest.raises(HTTPException) as error:
        predict_maintenance_endpoint(request)
    assert error.value.status_code == 422

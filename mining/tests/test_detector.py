import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from app.main import DetectRequest, detect


def test_detect_rejects_series_with_fewer_than_two_observations() -> None:
    with pytest.raises(HTTPException) as error:
        detect(DetectRequest(values=[10]))

    assert error.value.status_code == 422


def test_detect_finds_sustained_duration_change() -> None:
    result = detect(DetectRequest(values=[10.0] * 100 + [80.0] * 100))

    assert result.method == "IPDD + ADWIN"
    assert result.observation_count == 200
    assert result.smoothing_window == 10
    assert result.drifts
    assert result.drifts[0].index >= 100
    assert result.drifts[0].anomaly_start_index <= result.drifts[0].index
    assert result.drifts[0].anomaly_start_index <= 105


def test_detect_treats_an_isolated_outlier_without_hiding_a_real_drift() -> None:
    values = [10.0] * 80 + [1_000.0] + [10.0] * 79 + [80.0] * 160

    result = detect(DetectRequest(values=values))

    assert result.outlier_indices == [80]
    assert result.processed_values[80] < 100
    assert result.drifts
    assert result.drifts[0].index >= 150
    assert 145 <= result.drifts[0].anomaly_start_index <= 165


def test_detect_defaults_to_the_treated_pipeline() -> None:
    """An older caller that omits `treatment` must get what it always got."""
    result = detect(DetectRequest(values=[10.0] * 100 + [80.0] * 100))

    assert result.treatment == "treated"
    assert result.smoothing_window == 10


def test_raw_treatment_passes_the_series_through_untouched() -> None:
    """`raw` is what reproduces the synthetic baseline (adwin_dataset.py).

    The value of this mode is that nothing stands between the log and ADWIN,
    so the product's numbers can be checked against the published ones. If
    smoothing or outlier replacement ever leaked into it, the comparison would
    silently stop meaning anything — hence asserting on the series itself and
    not merely on the reported window.
    """
    values = [10.0] * 80 + [1_000.0] + [10.0] * 79 + [80.0] * 160

    result = detect(DetectRequest(values=values, treatment="raw"))

    assert result.treatment == "raw"
    assert result.smoothing_window == 1
    assert result.outlier_indices == []
    assert result.processed_values == values


def test_raw_and_treated_disagree_on_an_isolated_spike() -> None:
    """The spike survives into `raw` and is replaced in `treated`."""
    values = [10.0] * 80 + [1_000.0] + [10.0] * 79 + [80.0] * 160

    raw = detect(DetectRequest(values=values, treatment="raw"))
    treated = detect(DetectRequest(values=values, treatment="treated"))

    assert raw.processed_values[80] == 1_000.0
    assert treated.processed_values[80] < 100
    assert raw.drifts and treated.drifts


def test_detect_rejects_an_unknown_treatment() -> None:
    with pytest.raises(ValidationError):
        DetectRequest(values=[10.0, 11.0], treatment="suavizada")

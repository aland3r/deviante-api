from app.drift_detector import DriftAnalysis, DriftPoint, IpddAdwinAdapter
from app.ipdd_adwin import detect_performance_drifts


def test_adapter_translates_research_output_to_deviante_format() -> None:
    values = [10.0] * 100 + [80.0] * 100

    adapted = IpddAdwinAdapter().detect(values, delta=0.002, treatment="treated")
    original = detect_performance_drifts(values, delta=0.002, treatment="treated")

    assert isinstance(adapted, DriftAnalysis)
    assert adapted.method == "IPDD + ADWIN"
    assert adapted.drifts and all(isinstance(d, DriftPoint) for d in adapted.drifts)
    assert [d.index for d in adapted.drifts] == [d.detection_index for d in original.drifts]
    assert adapted.processed_values == original.processed_values

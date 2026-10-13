import pytest

from app.analysis_engine import AnalysisEngine
from app.drift_detector import DriftDetector, IpddAdwinAdapter


def test_always_the_same_instance() -> None:
    assert AnalysisEngine.get_instance() is AnalysisEngine.get_instance()


def test_direct_construction_is_refused() -> None:
    AnalysisEngine.get_instance()
    with pytest.raises(RuntimeError):
        AnalysisEngine(IpddAdwinAdapter())


def test_engine_talks_to_the_target_interface() -> None:
    detector = AnalysisEngine.get_instance().detector
    assert isinstance(detector, DriftDetector)
    assert isinstance(detector, IpddAdwinAdapter)

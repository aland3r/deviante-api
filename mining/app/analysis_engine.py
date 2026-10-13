"""Singleton (Reuso 03): uma única instância do motor de análise por processo
Python. Com o serviço "quente" (Fly.io hoje, Azure Function no alvo), o módulo
continua carregado entre requisições e o detector não é recriado a cada uma.
"""

from __future__ import annotations

from .drift_detector import DriftAnalysis, DriftDetector, IpddAdwinAdapter


class AnalysisEngine:
    _instance: AnalysisEngine | None = None

    def __init__(self, detector: DriftDetector) -> None:
        if AnalysisEngine._instance is not None:
            raise RuntimeError("Use AnalysisEngine.get_instance().")
        self.detector = detector

    @classmethod
    def get_instance(cls) -> AnalysisEngine:
        if cls._instance is None:
            cls._instance = cls(IpddAdwinAdapter())
        return cls._instance

    def run(self, values: list[float], *, delta: float, treatment: str) -> DriftAnalysis:
        return self.detector.detect(values, delta=delta, treatment=treatment)

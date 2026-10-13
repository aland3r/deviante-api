"""Adapter (Reuso 03): o código da pesquisa (IPDD/ADWIN, ``ipdd_adwin.py``)
tem interface própria. Este módulo define a interface que o Deviante espera,
``DriftDetector``, e o adaptador que traduz uma para a outra sem alterar
nenhuma linha do código adaptado ("envolver, não fazer fork").
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass

from .ipdd_adwin import TREATMENT_TREATED, detect_performance_drifts


@dataclass(frozen=True)
class DriftPoint:
    """Formato do Deviante para um ponto de drift."""

    index: int
    anomaly_start_index: int
    value: float
    width: float
    estimation: float


@dataclass(frozen=True)
class DriftAnalysis:
    """Formato do Deviante para o resultado de uma análise de drift."""

    method: str
    treatment: str
    smoothing_window: int
    processed_values: list[float]
    outlier_indices: list[int]
    drifts: list[DriftPoint]


class DriftDetector(ABC):
    """Interface alvo: o resto do sistema só conhece isto."""

    @abstractmethod
    def detect(self, values: list[float], *, delta: float, treatment: str) -> DriftAnalysis: ...


class IpddAdwinAdapter(DriftDetector):
    """Adapter: envolve ``detect_performance_drifts`` (código da pesquisa) e
    converte ``AdaptiveDetection``/``AdaptiveDrift`` para o formato do Deviante."""

    METHOD = "IPDD + ADWIN"

    def detect(
        self, values: list[float], *, delta: float = 0.002, treatment: str = TREATMENT_TREATED
    ) -> DriftAnalysis:
        result = detect_performance_drifts(values, delta=delta, treatment=treatment)
        return DriftAnalysis(
            method=self.METHOD,
            treatment=result.treatment,
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

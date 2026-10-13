"""Adapter (Reuso 03): a biblioteca pm4py descobre o grafo de sequência
direta (DFG) com interface própria (dicionários de pares). Este módulo define
a interface que o Deviante espera, ``GraphMiner``, e o adaptador que converte
a saída do pm4py em nós e arestas, o formato que o front desenha.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field

import pandas as pd


@dataclass
class ProcessGraph:
    """Formato do Deviante: nós e arestas com frequência."""

    nodes: list[dict] = field(default_factory=list)
    edges: list[dict] = field(default_factory=list)


class GraphMiner(ABC):
    """Interface alvo. ``events`` tem as colunas que o parser normaliza:
    ``case_id``, ``raw_label`` e ``occurred_at``."""

    @abstractmethod
    def mine(self, events: pd.DataFrame) -> ProcessGraph: ...


class Pm4pyGraphAdapter(GraphMiner):
    """Adapter: chama ``pm4py.discover_dfg`` e converte para ``ProcessGraph``."""

    def mine(self, events: pd.DataFrame) -> ProcessGraph:
        if events.empty:
            return ProcessGraph()
        import pm4py  # import tardio, como no parser.py: o pm4py é pesado

        dfg, starts, ends = pm4py.discover_dfg(
            events,
            case_id_key="case_id",
            activity_key="raw_label",
            timestamp_key="occurred_at",
        )
        activities = sorted({a for pair in dfg for a in pair} | set(starts) | set(ends))
        return ProcessGraph(
            nodes=[
                {"id": a, "label": a, "start": int(starts.get(a, 0)), "end": int(ends.get(a, 0))}
                for a in activities
            ],
            edges=[
                {"source": s, "target": t, "frequency": int(n)}
                for (s, t), n in sorted(dfg.items(), key=lambda item: (-item[1], item[0]))
            ],
        )

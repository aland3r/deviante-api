import pandas as pd

from app.graph_miner import GraphMiner, Pm4pyGraphAdapter, ProcessGraph


def _events(rows):
    frame = pd.DataFrame(rows, columns=["case_id", "raw_label", "occurred_at"])
    frame["occurred_at"] = pd.to_datetime(frame["occurred_at"], utc=True)
    return frame


def test_adapter_converts_pm4py_dfg_to_nodes_and_edges() -> None:
    events = _events([
        ("c1", "Corte", "2026-10-01T08:00:00Z"),
        ("c1", "Solda", "2026-10-01T08:10:00Z"),
        ("c1", "Pintura", "2026-10-01T08:30:00Z"),
        ("c2", "Corte", "2026-10-01T09:00:00Z"),
        ("c2", "Solda", "2026-10-01T09:12:00Z"),
    ])

    miner: GraphMiner = Pm4pyGraphAdapter()
    graph = miner.mine(events)

    assert isinstance(graph, ProcessGraph)
    assert {node["id"] for node in graph.nodes} == {"Corte", "Solda", "Pintura"}
    assert {"source": "Corte", "target": "Solda", "frequency": 2} in graph.edges
    assert {"source": "Solda", "target": "Pintura", "frequency": 1} in graph.edges
    corte = next(node for node in graph.nodes if node["id"] == "Corte")
    assert corte["start"] == 2


def test_empty_log_gives_empty_graph() -> None:
    assert Pm4pyGraphAdapter().mine(_events([])) == ProcessGraph()


def test_parse_endpoint_builds_the_graph_from_parsed_traces() -> None:
    from app.main import _event_frame
    from app.parser import parse_log
    from tests.test_parser import CSV_LOG

    result = parse_log(CSV_LOG, "log.csv")
    graph = Pm4pyGraphAdapter().mine(_event_frame(result.traces))

    labels = {event.raw_label for event in result.events}
    assert {node["id"] for node in graph.nodes} == labels
    transitions = sum(max(trace.event_count - 1, 0) for trace in result.traces)
    assert sum(edge["frequency"] for edge in graph.edges) == transitions

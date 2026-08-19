"""Parser tests (UC4).

Fixtures are written inline rather than pointing at
`deviante/Adaptive-Detection-of-Performance-Related-Temporal-Drifts-main/`:
that corpus is ~182 MB and not in git, so a suite that depended on it would
pass only on the machine that happens to have it unzipped. The shapes here
mirror the real files exactly — XES with `lifecycle:transition` start/complete
pairs, CSV with the `Prod1Torno.csv` column names and `;` separator.
"""

from __future__ import annotations

import pytest

from app.parser import ParseError, parse_log

CSV_LOG = "\n".join(
    [
        "Case;Atividade;Inicio;Fim;Tempo(s)",
        "1;Alimentacao de Maquina;19-01-2012 07:54:31;19-01-2012 07:54:46;15",
        "1;Maquina trabalhando;19-01-2012 07:54:46;19-01-2012 07:55:26;40",
        "1;Retirada do Produto;19-01-2012 07:55:26;19-01-2012 07:55:29;3",
        "2;Alimentacao de Maquina;19-01-2012 07:55:29;19-01-2012 07:55:47;18",
        "2;Maquina trabalhando;19-01-2012 07:55:47;19-01-2012 07:56:37;50",
    ]
).encode("utf-8")


def _xes(events: list[tuple[str, str, str, str]]) -> bytes:
    """Build a minimal XES log. Each event is (case, activity, transition, ts)."""
    by_case: dict[str, list[tuple[str, str, str]]] = {}
    for case, activity, transition, timestamp in events:
        by_case.setdefault(case, []).append((activity, transition, timestamp))

    traces = []
    for case, rows in by_case.items():
        entries = "".join(
            f'<event>'
            f'<string key="concept:name" value="{activity}"/>'
            f'<string key="lifecycle:transition" value="{transition}"/>'
            f'<date key="time:timestamp" value="{timestamp}"/>'
            f"</event>"
            for activity, transition, timestamp in rows
        )
        traces.append(
            f'<trace><string key="concept:name" value="{case}"/>{entries}</trace>'
        )

    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<log xes.version="1.0">' + "".join(traces) + "</log>"
    ).encode("utf-8")


XES_LOG = _xes(
    [
        ("1", "Raw_Material_Loading", "start", "2020-06-18T00:00:00.000+00:00"),
        ("1", "Raw_Material_Loading", "complete", "2020-06-18T00:00:10.000+00:00"),
        ("1", "Machine_Operating", "start", "2020-06-18T00:00:10.000+00:00"),
        ("1", "Machine_Operating", "complete", "2020-06-18T00:00:30.000+00:00"),
        ("2", "Raw_Material_Loading", "start", "2020-06-18T00:01:00.000+00:00"),
        ("2", "Raw_Material_Loading", "complete", "2020-06-18T00:01:12.000+00:00"),
    ]
)


class TestCsv:
    def test_distinct_events_and_traces(self):
        result = parse_log(CSV_LOG, "Prod1Torno.csv")

        assert result.format == "csv"
        assert result.trace_count == 2
        assert result.operation_count == 3

    def test_events_are_ranked_by_frequency(self):
        result = parse_log(CSV_LOG, "log.csv")
        labels = [event.raw_label for event in result.events]

        # Two occurrences each come first; the single-occurrence label last.
        assert labels[-1] == "Retirada do Produto"
        assert set(labels[:2]) == {"Alimentacao de Maquina", "Maquina trabalhando"}

    def test_duration_comes_from_the_tempo_column(self):
        result = parse_log(CSV_LOG, "log.csv")
        operating = next(e for e in result.events if e.raw_label == "Maquina trabalhando")

        assert operating.occurrence_count == 2
        assert operating.case_count == 2
        assert operating.mean_duration_seconds == pytest.approx(45.0)

    def test_trace_events_keep_chronological_order(self):
        result = parse_log(CSV_LOG, "log.csv")
        first = next(t for t in result.traces if t.case_id == "1")

        assert [e.raw_label for e in first.events] == [
            "Alimentacao de Maquina",
            "Maquina trabalhando",
            "Retirada do Produto",
        ]
        assert [e.sequence_index for e in first.events] == [0, 1, 2]

    def test_trace_span_covers_first_start_to_last_end(self):
        result = parse_log(CSV_LOG, "log.csv")
        first = next(t for t in result.traces if t.case_id == "1")

        assert first.event_count == 3
        assert first.duration_seconds == pytest.approx(58.0)

    def test_missing_columns_are_reported_not_swallowed(self):
        with pytest.raises(ParseError, match="colunas esperadas"):
            parse_log(b"foo;bar\n1;2", "broken.csv")

    def test_latin1_encoding_still_parses(self):
        latin = CSV_LOG.decode("utf-8").replace("Maquina", "Máquina").encode("latin-1")
        result = parse_log(latin, "latin.csv")

        assert any("Máquina" in e.raw_label for e in result.events)


class TestXes:
    def test_lifecycle_pairs_collapse_into_one_event(self):
        result = parse_log(XES_LOG, "ST_01.xes")

        assert result.format == "xes"
        assert result.trace_count == 2
        # start+complete of one activity is ONE event, not two.
        assert result.operation_count == 2

    def test_sojourn_time_is_derived_from_the_interval(self):
        result = parse_log(XES_LOG, "ST_01.xes")
        operating = next(e for e in result.events if e.raw_label == "Machine_Operating")

        # This is the number ADWIN consumes downstream (UC12) — if the
        # lifecycle collapse regresses, it silently becomes 0 and the drift
        # signal disappears rather than erroring.
        assert operating.mean_duration_seconds == pytest.approx(20.0)

    def test_trace_sequence_is_ordered(self):
        result = parse_log(XES_LOG, "ST_01.xes")
        first = next(t for t in result.traces if t.case_id == "1")

        assert [e.raw_label for e in first.events] == [
            "Raw_Material_Loading",
            "Machine_Operating",
        ]

    def test_unreadable_xes_is_a_parse_error(self):
        with pytest.raises(ParseError):
            parse_log(b"<log>not really xes", "broken.xes")


class TestDispatch:
    def test_unknown_extension_is_rejected(self):
        with pytest.raises(ParseError, match="Formato não suportado"):
            parse_log(b"anything", "log.xlsx")

    def test_extension_match_is_case_insensitive(self):
        result = parse_log(CSV_LOG, "PROD1TORNO.CSV")
        assert result.format == "csv"

    def test_a_file_with_no_readable_event_is_rejected(self):
        empty = b"Case;Atividade;Inicio;Fim;Tempo(s)\n"
        with pytest.raises(ParseError, match="nenhum evento"):
            parse_log(empty, "empty.csv")

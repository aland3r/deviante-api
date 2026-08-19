"""Event-log parsing (UC4).

Stateless compute only — see gestalt-kit/docs/architecture.md
§ Process mining exception. Nothing here reads or writes the database;
Kotlin persists everything this module returns.

Two input shapes are supported, matching the datasets the research group
provided (deviante/docs/README.md § Datasets de pesquisa):

- **XES** (`dataset_manufacturing/*.xes`) — IEEE 1849, read through PM4Py.
  Lifecycle `start`/`complete` pairs are collapsed into intervals so each
  event carries a duration, the same preprocessing Luiz's `adwin_dataset.py`
  does before feeding ADWIN.
- **CSV** (`real_dataset/Prod1Torno.csv`) — `;`-separated, Portuguese
  columns, one row per already-closed interval. PM4Py is not involved:
  the file has no lifecycle transitions to reconstruct.
"""

from __future__ import annotations

import io
from dataclasses import dataclass, field
from datetime import datetime

import pandas as pd

# Column names PM4Py normalizes XES attributes to.
XES_CASE = "case:concept:name"
XES_ACTIVITY = "concept:name"
XES_TIMESTAMP = "time:timestamp"
XES_DURATION = "@@duration"

# Columns in the shop-floor CSV (Prod1Torno.csv).
CSV_CASE = "Case"
CSV_ACTIVITY = "Atividade"
CSV_START = "Inicio"
CSV_END = "Fim"
CSV_DURATION = "Tempo(s)"
CSV_SEPARATOR = ";"
CSV_DATE_FORMAT = "%d-%m-%Y %H:%M:%S"


class ParseError(ValueError):
    """Input the caller can fix — surfaces as HTTP 422, not a 500."""


@dataclass
class ParsedEvent:
    """A distinct activity label found in the log, with its frequency.

    This is the raw side of the mapping the Manager resolves in UC5/UC6:
    the label is whatever the outsourced log called it and is never edited.
    """

    raw_label: str
    occurrence_count: int
    case_count: int
    total_duration_seconds: float
    mean_duration_seconds: float


@dataclass
class ParsedTraceEvent:
    raw_label: str
    sequence_index: int
    occurred_at: datetime | None
    duration_seconds: float | None


@dataclass
class ParsedTrace:
    case_id: str
    event_count: int
    started_at: datetime | None
    ended_at: datetime | None
    duration_seconds: float | None
    events: list[ParsedTraceEvent] = field(default_factory=list)


@dataclass
class ParsedLog:
    format: str
    events: list[ParsedEvent]
    traces: list[ParsedTrace]

    @property
    def operation_count(self) -> int:
        return len(self.events)

    @property
    def trace_count(self) -> int:
        return len(self.traces)


def parse_log(content: bytes, filename: str) -> ParsedLog:
    """Dispatch on file extension. Raises ParseError for anything else."""
    lowered = filename.lower()
    if lowered.endswith(".xes"):
        return _parse_xes(content, filename)
    if lowered.endswith(".csv"):
        return _parse_csv(content)
    raise ParseError(
        f"Formato não suportado: {filename}. Envie um arquivo .xes ou .csv."
    )


def _parse_xes(content: bytes, filename: str) -> ParsedLog:
    # Imported lazily: pm4py pulls in a heavy dependency tree, and the CSV
    # path must stay usable even where it is unavailable.
    import pm4py
    from pm4py.objects.log.util import interval_lifecycle

    import tempfile
    import os

    # pm4py.read_xes takes a path, not a buffer.
    handle, path = tempfile.mkstemp(suffix=".xes")
    try:
        with os.fdopen(handle, "wb") as tmp:
            tmp.write(content)
        try:
            log = pm4py.read_xes(path)
        except Exception as err:  # noqa: BLE001 — surface as 422, not 500
            raise ParseError(f"Não foi possível ler o XES: {err}") from err
    finally:
        # On Windows a failed read_xes can leave its handle open, and the
        # unlink then raises PermissionError — masking the real ParseError
        # with a 500. Losing a temp file is the lesser problem.
        try:
            os.unlink(path)
        except OSError:
            pass

    log = pm4py.convert_to_event_log(log)
    # start/complete -> a single row carrying @@duration, mirroring
    # adwin_dataset.py so sojourn time means the same thing downstream.
    log = interval_lifecycle.to_interval(log)
    df = pm4py.convert_to_dataframe(log)

    missing = [c for c in (XES_CASE, XES_ACTIVITY, XES_TIMESTAMP) if c not in df.columns]
    if missing:
        raise ParseError(
            f"{filename} não tem os atributos XES esperados: {', '.join(missing)}."
        )

    frame = pd.DataFrame(
        {
            "case_id": df[XES_CASE].astype(str),
            "raw_label": df[XES_ACTIVITY].astype(str),
            "occurred_at": pd.to_datetime(df[XES_TIMESTAMP], utc=True, errors="coerce"),
            "duration_seconds": (
                pd.to_numeric(df[XES_DURATION], errors="coerce")
                if XES_DURATION in df.columns
                else pd.Series([None] * len(df), dtype="float64")
            ),
        }
    )
    frame["ended_at"] = frame["occurred_at"]

    return _build_result("xes", frame)


def _parse_csv(content: bytes) -> ParsedLog:
    try:
        df = pd.read_csv(io.BytesIO(content), sep=CSV_SEPARATOR, encoding="utf-8")
    except UnicodeDecodeError:
        df = pd.read_csv(io.BytesIO(content), sep=CSV_SEPARATOR, encoding="latin-1")
    except Exception as err:  # noqa: BLE001
        raise ParseError(f"Não foi possível ler o CSV: {err}") from err

    df.columns = [str(c).strip() for c in df.columns]
    missing = [c for c in (CSV_CASE, CSV_ACTIVITY, CSV_START) if c not in df.columns]
    if missing:
        raise ParseError(
            "CSV sem as colunas esperadas: "
            + ", ".join(missing)
            + f". Esperado: {CSV_CASE};{CSV_ACTIVITY};{CSV_START};{CSV_END};{CSV_DURATION}."
        )

    started = _to_datetime(df[CSV_START])
    ended = _to_datetime(df[CSV_END]) if CSV_END in df.columns else started

    if CSV_DURATION in df.columns:
        duration = pd.to_numeric(df[CSV_DURATION], errors="coerce")
    else:
        duration = (ended - started).dt.total_seconds()

    frame = pd.DataFrame(
        {
            "case_id": df[CSV_CASE].astype(str),
            "raw_label": df[CSV_ACTIVITY].astype(str).str.strip(),
            "occurred_at": started,
            "ended_at": ended,
            "duration_seconds": duration,
        }
    )

    return _build_result("csv", frame)


def _to_datetime(series: pd.Series) -> pd.Series:
    parsed = pd.to_datetime(series, format=CSV_DATE_FORMAT, utc=True, errors="coerce")
    if parsed.isna().all():
        # Fall back to inference — a different export may use another layout.
        parsed = pd.to_datetime(series, utc=True, errors="coerce", dayfirst=True)
    return parsed


def _build_result(fmt: str, frame: pd.DataFrame) -> ParsedLog:
    """Collapse a normalized event frame into distinct labels + traces."""
    frame = frame.dropna(subset=["case_id", "raw_label"])
    frame = frame[frame["raw_label"].str.len() > 0]
    if frame.empty:
        raise ParseError("O arquivo não contém nenhum evento legível.")

    # Ordering events by timestamp is what makes the trace a sequence rather
    # than a bag; rows without a timestamp keep their file order at the end.
    frame = frame.sort_values(
        by=["case_id", "occurred_at"], na_position="last", kind="stable"
    )

    events = _distinct_events(frame)
    traces = _traces(frame)
    return ParsedLog(format=fmt, events=events, traces=traces)


def _distinct_events(frame: pd.DataFrame) -> list[ParsedEvent]:
    grouped = frame.groupby("raw_label", sort=False)
    events: list[ParsedEvent] = []

    for raw_label, group in grouped:
        durations = pd.to_numeric(group["duration_seconds"], errors="coerce").dropna()
        total = float(durations.sum()) if not durations.empty else 0.0
        mean = float(durations.mean()) if not durations.empty else 0.0
        events.append(
            ParsedEvent(
                raw_label=str(raw_label),
                occurrence_count=int(len(group)),
                case_count=int(group["case_id"].nunique()),
                total_duration_seconds=total,
                mean_duration_seconds=mean,
            )
        )

    events.sort(key=lambda e: (-e.occurrence_count, e.raw_label))
    return events


def _traces(frame: pd.DataFrame) -> list[ParsedTrace]:
    traces: list[ParsedTrace] = []

    for case_id, group in frame.groupby("case_id", sort=False):
        started = _min_timestamp(group["occurred_at"])
        ended = _max_timestamp(group["ended_at"])
        duration = (
            (ended - started).total_seconds()
            if started is not None and ended is not None
            else None
        )

        events = [
            ParsedTraceEvent(
                raw_label=str(row.raw_label),
                sequence_index=index,
                occurred_at=_as_datetime(row.occurred_at),
                duration_seconds=_as_float(row.duration_seconds),
            )
            for index, row in enumerate(group.itertuples(index=False))
        ]

        traces.append(
            ParsedTrace(
                case_id=str(case_id),
                event_count=len(events),
                started_at=started,
                ended_at=ended,
                duration_seconds=duration,
                events=events,
            )
        )

    traces.sort(key=lambda t: (t.started_at is None, t.started_at, t.case_id))
    return traces


def _min_timestamp(series: pd.Series) -> datetime | None:
    valid = series.dropna()
    return valid.min().to_pydatetime() if not valid.empty else None


def _max_timestamp(series: pd.Series) -> datetime | None:
    valid = series.dropna()
    return valid.max().to_pydatetime() if not valid.empty else None


def _as_datetime(value) -> datetime | None:
    if value is None or pd.isna(value):
        return None
    return pd.Timestamp(value).to_pydatetime()


def _as_float(value) -> float | None:
    if value is None or pd.isna(value):
        return None
    return float(value)

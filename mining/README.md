# Deviante — process-mining service

Stateless Python/FastAPI compute called **only by the Kotlin API**
(`deviante/api`), never by the browser. It parses uploaded event logs
(UC4) and returns the result; Kotlin persists it. See
gestalt-kit/docs/architecture.md § Process mining exception (sibling repo in
the gestalt-hub monorepo checkout).

PM4Py has no practical JVM equivalent for XES — that is the whole reason
this service exists. It is not a second home for business logic, and it
holds no database connection.

## Run

```bash
cd deviante/api/mining
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements.txt   # Windows
./.venv/Scripts/python.exe -m uvicorn app.main:app --port 8000
```

Or start it alongside the rest of the stack via the `deviante-mining`
entry in `.claude/launch.json`.

## Deploy

The production service runs as the private Fly app `deviante-mining`.
Deploy it from this directory:

```bash
flyctl apps create deviante-mining --org personal
flyctl ips allocate-v6 --private --app deviante-mining
flyctl deploy --app deviante-mining
```

The Kotlin app reaches it through Flycast:

```text
MINING_SERVICE_URL=http://deviante-mining.flycast
```

The service is not called by the browser and does not need a public IP.

The Kotlin API finds it through `mining.url` in `application.yaml`
(`MINING_SERVICE_URL`, default `http://localhost:8000`). Uploads fail with
**503** when it is unreachable and **422** when the file itself is bad —
the two are kept distinct so the Manager is not told to fix a file when the
service is simply down.

## Tests

```bash
./.venv/Scripts/python.exe -m pip install -r requirements-dev.txt
./.venv/Scripts/python.exe -m pytest tests/ -q
```

Fixtures are inline, not the 182 MB research corpus — the suite has to pass
on a clean checkout, and that corpus is not in git.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET`  | `/health` | Liveness. |
| `POST` | `/parse`  | Multipart `file` → distinct event labels + ordered traces. |
| `POST` | `/detect` | Treated IPDD/ADWIN series, adaptive windows and drift points. |

## IPDD + ADWIN analysis

The online detector is implemented in `app/ipdd_adwin.py`, adapted from Luiz
Picolo's separate research scripts under
`Adaptive-Detection-of-Performance-Related-Temporal-Drifts-main/`. It keeps
the research flow while making the outlier treatment process-agnostic:

1. replace isolated local spikes without flattening sustained changes;
2. apply the same adaptive rolling-window rule used by the real-dataset script;
3. stream the treated values through River ADWIN;
4. return both the estimated start of the new adaptive window and the later
   point where ADWIN confirms the drift.

## Supported inputs

Both shapes come from the research datasets in
[`docs/README.md § Datasets de pesquisa`](../docs/README.md#datasets-de-pesquisa-luiz-picolo):

- **XES** (`dataset_manufacturing/*.xes`) — read through PM4Py. Lifecycle
  `start`/`complete` pairs are collapsed into intervals (`@@duration`), the
  same preprocessing `adwin_dataset.py` applies before feeding ADWIN, so
  sojourn time means the same thing here as it will in UC12.
- **CSV** (`real_dataset/Prod1Torno.csv`) — `;`-separated, Portuguese
  columns (`Case`, `Atividade`, `Inicio`, `Fim`, `Tempo(s)`). PM4Py is not
  involved: the rows are already closed intervals, there is no lifecycle to
  reconstruct.

## Verified output

Checked over HTTP against the real files:

| File | Distinct events | Traces |
|------|-----------------|--------|
| `ST_01.xes` | 4 | 500 |
| `DR_01.xes` | 4 | 500 |
| `Prod1Torno.csv` | 12 | 3282 |

`Machine_Operating` comes out at **11.1 s** mean sojourn in `ST_01`
(the stable control series) and **251.5 s** in `DR_01` (drift injected at
trace 10). That gap is the signal ADWIN consumes — it is the cheapest
regression check that the preprocessing is still correct.

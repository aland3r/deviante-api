# backend

This project was created using the [Ktor Project Generator](https://start.ktor.io).

Here are some useful links to get you started:
 * [Ktor Documentation](https://ktor.io/docs/home.html)
 * [Ktor GitHub page](https://github.com/ktorio/ktor)
 * [Ktor Slack chat](https://app.slack.com/client/T09229ZC6/C0A974TJ9). [Request an invite](https://surveys.jetbrains.com/s3/kotlin-slack-sign-up).


## Features
Here's a list of features included in this project:

| Name | Description |
|------|-------------|

## Building & Running
To build or run the project, use one of the following tasks:


| Task | Description |
|------|-------------|
| `./gradlew test`    | Run the tests     |
| `./gradlew build`   | Build the project |
| `./gradlew run`     | Run the server    |

If the server starts successfully, you'll see the following output:
```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## Deploy (Fly.io)

`.github/workflows/deploy.yml` deploys to Fly.io on every push to `main`.
Set these as **repo secrets** (Settings → Secrets and variables → Actions)
before the first push:

| Secret | Where to find it |
|--------|-------------------|
| `FLY_API_TOKEN` | `fly tokens create deploy` (or Fly dashboard → Account → Access Tokens) |
| `DATABASE_JDBC_URL` | Same value as local `.env` — Supabase → Project Settings → Database (Session pooler) |
| `DATABASE_USER` | Same as local `.env` |
| `DATABASE_PASSWORD` | Same as local `.env` |
| `SUPABASE_URL` | Same as local `.env` — used to verify bearer tokens against Supabase Auth |
| `SUPABASE_ANON_KEY` | Same as local `.env` — anon/publishable key, never `service_role` |
| `MINING_SERVICE_URL` | Private Flycast URL for the FastAPI parser: `http://deviante-mining.flycast` |

The workflow creates the Fly app (`deviante-api`, region `gru`) on first run
if it doesn't exist yet, stages the secrets, then deploys via Fly's remote
builder (no local Docker needed). After the first successful deploy, set
`VITE_API_URL=https://deviante-api.fly.dev/api` in `deviante-web`'s Vercel
env so the frontend stops hitting a same-origin `/api` path that nothing
proxies (routes are mounted under `/api` here — see `Routing.kt`).

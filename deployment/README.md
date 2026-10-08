# Production deployment: Vercel + Render

The recommended setup is **Vercel frontend + one Render web service + PostgreSQL**.
The Render web service runs the API and worker jobs in one Java process. No separate
paid background worker is required. A split deployment remains available below.

## Combined service on Render

Update your existing API web service after committing and pushing these changes:

| Render setting | Value |
|---|---|
| Root Directory | `backend` |
| Dockerfile Path | `combined/Dockerfile` |
| Docker Build Context | `.` |
| Health Check Path | `/health/readiness` |
| Docker Command | Leave blank |

Keep the existing database and API hostname. Keep these environment values unchanged:

| Setting | Value |
|---|---|
| SPRING_PROFILES_ACTIVE | `prod` |
| DB_URL | `jdbc:postgresql://HOST:5432/DATABASE?sslmode=require` |
| DB_USERNAME / DB_PASSWORD | PostgreSQL credentials, stored only in Render |
| JWT_SECRET | Your existing private random signing secret |
| CORS_ALLOWED_ORIGINS | Exact HTTPS Vercel/custom-domain origins, comma-separated, no trailing slash |
| DB_POOL_SIZE | Optional; defaults to 5 connections shared by API and jobs |
| PORT | Supplied by Render |

Leave `SPRING_CONFIG_NAME` unset: the combined entry point selects `combined.yml`.
Leave `APP_WORKER_ENABLED` unset or true. The combined configuration imports the API's
shared settings, retaining security, migration validation, and production checks.
Two scheduler threads allow planning deadlines and battle processing to progress
independently. The standalone worker application and its security exclusions are
not loaded. API and worker services share one connection pool and clock.

Use `production.env.example` only as a checklist. Missing settings fail startup;
production rejects short/known local JWT secrets and non-HTTPS or wildcard origins.
Generate a new secret with `openssl rand -hex 32` only for a new deployment; changing
the existing secret invalidates current login tokens. Never put real secrets in Git
or a `VITE_*` variable. Use Render's internal PostgreSQL hostname in the same region;
keep credentials separate from the JDBC URL. The example requires database TLS;
`sslmode=verify-full` with a trusted provider CA can additionally verify the server.

## Release order and switching from separate services

1. Keep the existing PostgreSQL database and its data. Back it up before schema changes.
2. If a separate worker is running, stop it during the switch. Do not run both
   deployment modes together. Rounds remain persisted while processing is stopped.
3. Deploy the combined image to the existing API web service. Flyway applies pending
   migrations before schema validation and before scheduled jobs start.
4. Wait for `/health/readiness` to return HTTP 200 and `{"status":"UP"}`.
5. Play a complete match from two browsers to verify embedded worker processing.

No new migrations or account resets are required by the combined-service change.
The API URL stays unchanged, so an already configured Vercel frontend needs no URL
change. For a first deployment, configure Vercel as described below.

Flyway clean is disabled and baseline-on-migrate is false. Never edit already-applied
migrations or bypass a checksum error by cleaning/baselining production. Use additive
migrations compatible with old instances during rollout; a code rollback does not
undo a database migration. No separate pre-deploy command is needed for the current
startup migration approach.

## Vercel and HTTPS

Set the Vercel root directory to `frontend`, framework to Vite, Node to 22, and install
command to `npm ci`. The checked-in `vercel.json` builds `dist` and handles nested
routes on refresh. Set `VITE_API_BASE_URL=https://YOUR-API.onrender.com/api` for the
production build and redeploy when it changes. This value is public, not a secret.
Browser API requests go directly to Render; the stable frontend origin must match
the CORS allowlist. Arbitrary preview domains are not allowed.

Render terminates HTTPS and forwards HTTP to the container. The prod profile honors
forwarded headers, so use it only behind the trusted hosting proxy. Verify public
HTTPS after deployment; do not expose the container directly or add certificates
inside the application.

## Health and operational limits

- `/health/readiness` checks process readiness and PostgreSQL connectivity; database
  outages return 503 and recovery returns UP.
- `/health/liveness` checks process state independently of database connectivity.
- `/health` is the aggregate endpoint used by local Compose.

Health endpoints expose status and probe-group names, not database internals.
Readiness does not prove that combat jobs are progressing; a played round does.
One process means shared CPU and memory. Start with one instance and inspect resource
usage during actual matches. If hosting suspends it, both API and jobs pause until
it resumes; this does not provide an always-on guarantee.

## Optional separate deployment

The original modules still work independently. Use the same Render root `backend`
and Docker build context `.` with `api/Dockerfile` for the API web service and
`worker/Dockerfile` for a background worker. Deploy the API first, wait for readiness,
then deploy the worker from the same commit. Both need prod/database settings; only
the API needs JWT/CORS values. The standalone worker has no HTTP health endpoint.
The worker's executable artifact now has a `-boot.jar` suffix; its thin jar is used
as a dependency by the combined module.

## References

- [Render Docker](https://render.com/docs/docker)
- [Render root-relative paths](https://render.com/docs/monorepo-support)
- [Render health checks](https://render.com/docs/health-checks)
- [Vercel Vite](https://vercel.com/docs/frameworks/frontend/vite)

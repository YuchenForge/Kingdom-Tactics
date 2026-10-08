# Production configuration

Step 4 prepares configuration only. No cloud resources have been created and no
real credentials are stored here. The API and worker use the existing Dockerfiles;
the frontend is a Vite build on Vercel.

## Required settings

Set **SPRING_PROFILES_ACTIVE=prod** separately on the API and worker. Do not combine
prod with dev/test. Use `production.env.example` as a checklist, not as a deployable
secret file. Missing datasource settings fail startup; neither service defaults to
local database credentials in prod. The API also rejects missing/short JWT secrets,
the known local secret, missing allowed origins, wildcards, and non-HTTPS origins.

| Setting | Service | Value |
|---|---|---|
| DB_URL | API + worker | JDBC URL for the same production PostgreSQL database |
| DB_USERNAME / DB_PASSWORD | API + worker | Database credentials from Render |
| DB_POOL_SIZE | API + worker | Defaults to 5 per process; size the total for your database connection limit |
| JWT_SECRET | API only | A private random value, at least 32 UTF-8 bytes |
| CORS_ALLOWED_ORIGINS | API only | Comma-separated exact HTTPS frontend origins, no trailing slash or path |
| PORT | API only | Supplied by Render; API listens on 0.0.0.0 |
| VITE_API_BASE_URL | Vercel build | `https://YOUR-API.onrender.com/api` |

Generate a JWT secret locally with `openssl rand -hex 32` and save it directly in
Render's secret settings. Keep it stable across API redeployments; rotation signs
all existing users out. Do not copy secrets into frontend variables: every `VITE_*`
value is public in the browser bundle. Rebuild Vercel after changing its API URL.

Render supplies PostgreSQL URLs in `postgresql://...` form. Use
`jdbc:postgresql://HOST:5432/DATABASE?sslmode=require`, with credentials in the two
separate variables. Use Render's internal host when API/worker/database share a
region. TLS is required by this example; for stronger certificate verification use
`sslmode=verify-full` with the provider's trusted CA as appropriate. Do not paste a
credential-bearing connection string into logs or source files.

## Render services (configure during Step 5)

- API: Docker web service, repository root directory, Docker context `./backend`,
  Dockerfile `./backend/api/Dockerfile`. Use `/health/readiness` as the health-check
  path. Leave Docker start command at its default.
- Worker: Docker **background worker**, same context, Dockerfile
  `./backend/worker/Dockerfile`. It has no HTTP listener or HTTP health check.
- PostgreSQL: same region as both services, with backups appropriate to saved games.
- Initially use one API and one worker instance. Deploy them from the same commit.
  Keep automatic independent deployments off until coordinated releases are set up.

Render terminates public HTTPS and forwards HTTP to the container. The API honors
forwarded headers in prod; only run this setting behind the trusted platform proxy.
Use the HTTPS API hostname in the frontend. Do not configure application certificates
or expose the container port directly to the internet. Platform HTTPS redirects and
certificates must be verified after deploying; they cannot be checked locally.

## Vercel frontend

Choose `frontend` as the Vercel project root, Vite as the framework, Node 22, install
command `npm ci`, and set `VITE_API_BASE_URL` for the production environment. The
checked-in `frontend/vercel.json` builds `dist` and handles nested SPA routes such as
join links and game pages on refresh. Browser API requests go directly to Render.
Set the stable Vercel/custom-domain origin in the API's CORS allowlist. Arbitrary
preview URLs are not allowed; add a specific HTTPS preview origin only when needed.
The CORS policy allows GET/POST/OPTIONS and Authorization, Content-Type, and
Idempotency-Key headers. It does not enable cross-site cookies.

## Migration and release order

1. Provision PostgreSQL and set the production environment values.
2. Deploy the API first. Flyway applies pending versioned migrations before Hibernate
   validates the schema and readiness becomes UP. The API is the migration owner.
   Flyway clean is disabled and baseline-on-migrate is false.
3. Wait for `/health/readiness` to return HTTP 200 and `{"status":"UP"}`.
4. Deploy/restart the worker from the same commit. It never migrates; it validates
   schema on startup and exits if the schema is missing or incompatible. Check the
   worker startup logs, then verify that a locked round resolves.
5. Deploy the frontend and run the two-player live smoke check.

For later schema changes, take a backup and use additive migrations compatible
with the old running API/worker during rollout. Do not edit already-applied migration
files, enable clean/baseline to bypass errors, or assume rolling back application
code reverses a database migration. A destructive schema change needs a separate
maintenance/recovery plan. A schema mismatch must stop the rollout for investigation.
No pre-deploy command is needed for this initial API-owned migration approach.

## Health and readiness

- `/health/readiness`: process readiness **and database connectivity**, returns 503
  when unavailable. Use this for deploy routing.
- `/health/liveness`: application process state only; a database outage does not
  imply the process is dead.
- `/health`: aggregate health, retained for local Compose compatibility.

These endpoints are public but expose no database details. The aggregate endpoint
also lists the available probe group names. Other
Actuator endpoints remain unexposed/protected. Readiness does not verify worker
progress: use worker logs and a resolved round for that check. A database outage
makes the API unready; restoration should return it to UP without a new deployment.

## Reference documentation

- [Render Docker builds](https://render.com/docs/docker)
- [Render health checks](https://render.com/docs/health-checks)
- [Render deployments](https://render.com/docs/deploys)
- [Vercel Vite deployments](https://vercel.com/docs/frameworks/frontend/vite)
- [Spring Boot health endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)

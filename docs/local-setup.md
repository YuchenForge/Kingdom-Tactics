# Local setup

The maintained full-stack and hot-reload instructions are in
[README — Run locally](../README.md#run-locally).

For the complete local game, start Docker and run from the repository root:

```bash
docker compose up --build -d --wait
```

Open http://localhost:5173. The combined API applies database migrations on startup
and runs the worker jobs in that same process. See the README for ports, logs,
persistence, resets, and configuration.

## Tests

Backend tests require Java 21, Maven, and Docker for Testcontainers:

```bash
cd backend
mvn test
```

Frontend checks require Node.js 22.12+:

```bash
cd frontend
npm ci
npm run lint
npm run build
npm test
```

The multiplayer browser check is `npm run test:e2e` in `frontend/`. See the root README for the disposable Compose stack it expects.

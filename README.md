# Kingdom Tactics

A deployable, server-authoritative 1v1 turn-based tactical auto-battler. Players recruit units from a rotating shop, place them on a grid, and lock their formation. The backend runs deterministic combat simulation and persists outcomes.

This project is designed to showcase transactional backends, deterministic domain engines, secure command processing, and production-grade observability.

**Status:** Phase 6 complete as of 2026-10-07. Implementation and automated checks are complete; the user confirmed two-browser automatic combat, deadline expiry, reload, return to planning, and final result. Phase 7 local-stack setup is in progress.

---

## Quick links

- **[Game Rules](docs/rules.md)** — Complete, unambiguous mechanics
- **[Architecture Decision Records](docs/adr/)** — Why we made key choices
- **[Combat Scenarios](docs/combat-scenarios.md)** — Test cases for validation
- **[State Machine](docs/state-machine.md)** — Game lifecycle and transitions
- **[Roadmap](ROADMAP.md)** — 8-phase development plan
- **[Local Setup](docs/local-setup.md)** — Dev environment guide
- **[API Contract](docs/api-contract.md)** — REST endpoint specifications
- **[Database Schema](docs/database-schema.md)** — Tables and constraints

---

## Project philosophy

**Correctness over cleverness.** The backend must be trustworthy: all actions are logged, combat is deterministic and replayable, and outcomes are auditable. Players should have confidence that the opponent cannot cheat.

**Server-authoritative state.** The server is the single source of truth. Clients are trusted to display information, not to compute outcomes.

**Deterministic combat.** Same input (seed, board state, rules version) always produces the same output. This enables:
- Perfect replay from stored events
- Bug reproduction
- Rules version migrations
- Offline validation

---

## Tech stack

| Layer | Technology |
|-------|------------|
| Frontend | React, TypeScript, Vite, Tailwind CSS, TanStack Query |
| Backend | Java 21, Spring Boot 3, PostgreSQL |
| Testing | JUnit 5, Testcontainers, Vitest, Playwright |
| Deployment | Docker, GitHub Actions, Render/Railway |

---

## Getting started

### Full local stack (Docker only)

Install Docker with Docker Compose and start Docker. From a fresh checkout, run:

```bash
docker compose up --build -d --wait
```

Open **http://localhost:5173**. Use two browsers or an incognito window to register
separate accounts and play. No local Java, Maven, Node, or `.env` file is required.
The first build downloads dependencies and may take several minutes.

Compose starts PostgreSQL, then the combined API/worker (which applies Flyway
migrations), then the frontend after the database-backed `/health` check passes.
The frontend serves a production build and proxies `/api` to the API, so browser
requests stay on the same origin. Nested page URLs also work after refresh.
The combined service runs the worker jobs internally; playing a round verifies processing.

```bash
docker compose ps
docker compose logs -f api
curl --fail http://localhost:8080/health
docker compose down
```

Stopping preserves accounts and matches in the `postgres_data` volume. The API
applies pending migrations on the next start; the worker does not run migrations.
Do not change the Compose project name if you want to reuse an existing volume.
`docker compose down --volumes` permanently deletes that project's local game data;
use it only when intentionally resetting a disposable database. A migration checksum
error on an old pre-production database requires investigating its history or an
intentional reset, not simply restarting containers.

Ports default to frontend 5173, API 8080, and database 5432, bound to localhost.
To avoid other running development services, override them for the command:

```bash
KT_WEB_PORT=5174 KT_API_PORT=8081 KT_DB_PORT=5433 docker compose up --build -d --wait
```

Use the same overrides for subsequent Compose commands. These are the only optional
Compose settings; all database credentials and the JWT secret are disposable local
defaults defined in `docker-compose.yml`, unsuitable for public deployment. Existing
root or frontend `.env` secrets are not copied into images. After editing source,
rerun the startup command to rebuild; container mode does not hot reload.

### Development with hot reload

Requires Java 21, Maven 3.9+, and Node.js 22.12+ in addition to Docker.
Stop the full Compose stack before running host services on the same ports.

```bash
docker compose up -d postgres
cd backend
mvn install -DskipTests
mvn -pl api spring-boot:run
# Another terminal in backend/, once the API has started:
mvn -pl worker spring-boot:run
# Another terminal in frontend/:
npm ci
npm run dev
```

Use `VITE_API_BASE_URL=/api` (the default); Vite proxies requests to port 8080.
Do not combine `-am` with `spring-boot:run`. When running the API on the host,
run the worker on the host too: the Compose worker depends on the Compose API.

---

## Project phases

| Phase | Goal | Status |
|-------|------|--------|
| **0** | Design & specification | ✅ Complete |
| **1** | Pure combat engine | ✅ Complete |
| **2** | Backend foundation | ✅ Complete |
| **3** | Planning & commands | ✅ Complete |
| **4** | Worker & resolution | ✅ Complete |
| **5** | Frontend MVP | ✅ Complete |
| **6** | Combat animation | ✅ Complete |
| **7** | Deploy & polish | 📝 Next |

See [ROADMAP.md](ROADMAP.md) for details on each phase.

---

## Architecture overview

```
React + TypeScript client
  ├─ Tailwind CSS + CSS Grid
  ├─ TanStack Query: server-state fetching
  ├─ React useState: local selection/drag
  └─ Combat animation: React, CSS, and shared-clock recorded-event playback
            │
            ▼
Java / Spring Boot API
  ├─ REST command endpoints
  ├─ Authentication & authorization
  └─ Game query endpoints
            │
     ┌──────┴───────────────────────┐
     ▼                              ▼
PostgreSQL                        Worker
  │                                 │
  ├─ users, games, rounds           ├─ Claim LOCKED (SKIP LOCKED)
  ├─ commands, events, snapshots    ├─ Combat engine
  └─ ratings (±25 Phase 4)          └─ Persist + advance
```

See [docs/architecture.md](docs/architecture.md) for detailed diagrams.

---

## Key design decisions

1. **Server-authoritative state** — Server computes all outcomes; clients cannot cheat ([ADR 001](docs/adr/001-server-authoritative-state.md))

2. **Event logs + snapshots** — Both stored per round for perfect auditability and replay ([ADR 002](docs/adr/002-event-log-and-snapshots.md))

3. **Deterministic combat** — Seeded RNG, fixed-point math, stable tie-breaks ensure reproducibility ([ADR 003](docs/adr/003-deterministic-combat.md))

---

## Contributing

See [CONTRIBUTING.md](.github/CONTRIBUTING.md) for guidelines on commits, PRs, code style, and testing.

---

## Rules at a glance

- **Players:** 2
- **Match length:** up to 8 rounds
- **Board:** 4×4 placement grid per player; merges into 4×8 combat board (P0 rotated 180°, stacked vertically)
- **Holding lane:** 5 slots per player for shop purchases before placement
- **Starting HP:** 20 per Keep
- **Starting gold:** 10
- **Units:** Squire, Shieldbearer, Ranger, Knight, Mage, Healer
- **Win condition:** Reduce opponent Keep HP to 0, or higher HP after round 8

**Full rules:** [docs/rules.md](docs/rules.md)

---

## Development workflow

1. Read [docs/rules.md](docs/rules.md) and [docs/architecture.md](docs/architecture.md)
2. Check the current phase in [ROADMAP.md](ROADMAP.md)
3. Open the relevant task issues on GitHub
4. Write tests first (TDD)
5. Implement the feature
6. Run full test suite locally
7. Submit PR with description linking to the issue

---

## Debugging

### Replay a match (Phase 4+)

Participants can page `GET /api/games/{gameId}/events?round=&afterSequence=`. Round/match summaries: `GET .../rounds/{n}/result`, `GET .../result`. Phase 6 animates combat automatically during each round; a historical replay viewer is outside its scope.

### View game state (available now)

```bash
curl http://localhost:8080/api/games/{gameId}/state \
  -H "Authorization: Bearer <token>"
```

### Health checks

`GET /health` reports API and database health without authentication or internal details. Compose waits for it before starting the frontend; the worker jobs run inside the combined backend.

---

## FAQ

**Q: Why deterministic combat?**
A: It enables perfect replay, auditability, and testing. The same inputs always produce the same output—players can verify fairness, and we can catch bugs by replaying matches.

**Q: What if the backend goes down?**
A: Players cannot play, but their matches are safe. Once the backend recovers, they resume the round they were in. Complete matches are fully replayable.

**Q: Can I add new units later?**
A: Yes. Unit stats live in the engine as in-code `UnitDefinition` factories (not a DB table). Each round stores a `rules_version` string (currently always `"1.0"`); multi-version historical rule replay is not implemented yet.

**Q: Is this a commercial game?**
A: No. This is a portfolio project demonstrating full-stack SWE: transactional systems, determinism, observability, and production-grade reliability.

---

## License

MIT

---

## Contact

For questions or issues, open a GitHub issue or discussion.

---

**Next step:** [Phase 7 — Quality, deployment, and portfolio polish](ROADMAP.md#phase-7-quality-deployment-and-portfolio-polish).

## Multiplayer browser checks

The `Multiplayer E2E` GitHub Actions workflow builds the combined backend, frontend, and PostgreSQL against a
fresh database, then uses two separate Chromium sessions to register, invite/join,
recruit and deploy units, reload, lock boards, watch automatic combat, and finish
a match. No API responses or combat recordings are mocked. Failed runs upload
browser diagnostics and service logs; cleanup always removes the CI database.

To run locally against a separate disposable stack:

```bash
KT_WEB_PORT=15173 KT_API_PORT=18080 KT_DB_PORT=15432 docker compose -p kt-e2e up --build -d --wait
cd frontend
npm ci
npx playwright install chromium
npm run test:e2e
```

The test defaults to `http://127.0.0.1:15173`; override `E2E_BASE_URL` to use another
test stack. It creates accounts and matches, so use a disposable database. Allow up
to 12 minutes for a complete eight-round match. Unit tests remain `npm test`.
From the repository root, remove only this disposable stack when finished:

```bash
docker compose -p kt-e2e down --volumes
```

## Production preparation

See [production configuration](deployment/README.md) for required environment settings,
Vercel/Render setup, HTTPS, health checks, and the API-first migration sequence.
Cloud deployment is a separate step. Never use Compose credentials in production.

## One-service backend deployment

The default Compose stack now runs API and worker jobs in **one Java process**.
Use `backend/combined/Dockerfile` for Render. See [combined deployment](deployment/README.md#combined-service-on-render).
API settings remain shared with the standalone API; no schema or game-rule changes
are required. Worker services retain their own module for a future separate deployment.

To run the optional original split stack instead, set the API Dockerfile explicitly:

```bash
KT_API_DOCKERFILE=api/Dockerfile docker compose --profile separate-worker up --build -d --wait
```

When switching an existing local split stack to combined mode, first stop its separate
worker with `docker compose stop worker`, then run the normal startup command. This
keeps one worker scheduler active. For host development, use
`mvn -pl combined spring-boot:run` after `mvn install -DskipTests`; do not start the
standalone API or worker alongside it. Combined configuration uses `combined.yml`,
which imports the shared API defaults, and `combined-prod.yml` for production.

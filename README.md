# Kingdom Tactics

A deployable, server-authoritative 1v1 turn-based tactical auto-battler. Players recruit units from a rotating shop, place them on a grid, and lock their formation. The backend runs deterministic combat simulation and persists outcomes.

This project is a **full-stack SWE system** first, a game second—designed to showcase transactional backends, deterministic domain engines, secure command processing, and production-grade observability.

**Status:** Phase 0 (design & specification) complete. Ready for Phase 1 implementation.

---

## Quick links

- **[Game Rules](docs/rules.md)** — Complete, unambiguous mechanics
- **[Architecture Decision Records](docs/adr/)** — Why we made key choices
- **[Combat Scenarios](docs/combat-scenarios.md)** — 15 test cases for validation
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
| Frontend | React, TypeScript, Vite, Tailwind CSS, TanStack Query, Zustand |
| Backend | Java 21, Spring Boot 3, PostgreSQL, Redis |
| Testing | JUnit 5, Testcontainers, Vitest, Playwright |
| Deployment | Docker, GitHub Actions, Render/Railway |

---

## Getting started

### Prerequisites

- Java 21
- Node.js 18+
- Docker & Docker Compose
- PostgreSQL 15+
- Redis 7+

### Local development

```bash
# Clone the repo
git clone https://github.com/your-username/kingdom-tactics.git
cd kingdom-tactics

# Copy environment file
cp .env.example .env

# Start services (Docker)
docker-compose up -d

# Backend setup
cd backend
./mvnw clean install
./mvnw spring-boot:run

# Frontend setup (new terminal)
cd frontend
npm install
npm run dev
```

The game will be available at `http://localhost:5173`.

### Running tests

```bash
# Backend unit tests
cd backend && ./mvnw test

# Backend integration tests (requires Docker)
cd backend && ./mvnw verify

# Frontend unit tests
cd frontend && npm run test

# E2E tests
cd frontend && npm run test:e2e
```

---

## Project phases

| Phase | Goal | Status |
|-------|------|--------|
| **0** | Design & specification | ✅ Complete |
| **1** | Pure combat engine | 📝 Next |
| **2** | Backend foundation | ⏳ Planned |
| **3** | Planning & commands | ⏳ Planned |
| **4** | Worker & resolution | ⏳ Planned |
| **5** | Frontend MVP | ⏳ Planned |
| **6** | Replay & animation | ⏳ Planned |
| **7** | Deploy & polish | ⏳ Planned |
| **8** | One expansion | ⏳ Planned |

See [ROADMAP.md](ROADMAP.md) for details on each phase.

---

## Architecture overview

```
React + TypeScript client
  ├─ Tailwind CSS + CSS Grid
  ├─ TanStack Query: server-state fetching
  ├─ Zustand: local UI state
  └─ Framer Motion: animations
            │
            ▼
Java / Spring Boot API
  ├─ REST command endpoints
  ├─ Authentication & authorization
  ├─ Game query endpoints
  └─ WebSocket/SSE (later phases)
            │
     ┌──────┼───────────────────────┐
     ▼      ▼                       ▼
PostgreSQL  Redis                 Worker
  │          │                      │
  │          ├─ job queue            ├─ round resolution
  │          ├─ rate limiting        ├─ combat engine
  │          └─ caching              └─ event persistence
  ▼
Flyway migrations
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
- **Board:** 4×4 grid per player
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

### Replay a match

All matches are stored with full event logs. To debug a specific match:

```bash
curl http://localhost:8080/api/games/{gameId}/replay
```

This returns the complete round-by-round event log. Use it to:
- Verify combat outcomes
- Reproduce bugs
- Validate rule changes

### View game state

```bash
curl http://localhost:8080/api/games/{gameId}/state
```

Returns current board, Keep HP, gold, units, and planning phase details.

### Check backend health

```bash
curl http://localhost:8080/health
curl http://localhost:8080/health/games
```

---

## FAQ

**Q: Why deterministic combat?**
A: It enables perfect replay, auditability, and testing. The same inputs always produce the same output—players can verify fairness, and we can catch bugs by replaying matches.

**Q: What if the backend goes down?**
A: Players cannot play, but their matches are safe. Once the backend recovers, they resume the round they were in. Complete matches are fully replayable.

**Q: Can I add new units later?**
A: Yes. New units are added to the unit definitions table, and a new `rules_version` is recorded with each round. Old matches remain valid under their original rules version.

**Q: Is this a commercial game?**
A: No. This is a portfolio project demonstrating full-stack SWE: transactional systems, determinism, observability, and production-grade reliability.

---

## License

MIT

---

## Contact

For questions or issues, open a GitHub issue or discussion.

---

**Next step:** Start [Phase 1 — Pure Deterministic Combat Engine](ROADMAP.md#phase-1).

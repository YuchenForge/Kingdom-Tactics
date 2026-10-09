# Project roadmap

Kingdom Tactics has shipped its eight-phase MVP: a playable two-player game with deterministic combat, automatic animation, persistent matches, CI, and deployment configuration.

[Play the game](https://kingdom-tactics.vercel.app) · [Run locally](README.md#run-locally) · [Architecture](docs/architecture.md)

## Delivered

| Phase | Delivered capability | Reference |
|---|---|---|
| 0 — Specification | Rules, board coordinates, state transitions, and 15 combat scenarios | [Rules](docs/rules.md), [scenarios](docs/combat-scenarios.md) |
| 1 — Combat engine | Fixed-tick combat, targeting, BFS movement, abilities, deterministic outcomes, and JSON CLI | `backend/engine/` |
| 2 — Backend foundation | JWT authentication, participant access, create/join, PostgreSQL, and Flyway | [Schema](docs/database-schema.md) |
| 3 — Planning | Buy, sell, refresh, relocate, lock, automatic merging, deadlines, and idempotency | [API contract](docs/api-contract.md) |
| 4 — Resolution | Claim/simulate/commit/advance pipeline, crash recovery, historical results, and rating updates | [State machine](docs/state-machine.md) |
| 5 — Playable frontend | Landing, authentication, invites, lobby, planning board, and match results | `frontend/src/pages/` |
| 6 — Animation | Shared server clock, automatic event playback, reconnect catch-up, both seat orientations, and reduced motion | `frontend/src/combat/`, `frontend/src/hooks/` |
| 7 — Delivery | Compose full stack, combined API/worker, health probes, production settings, and multiplayer browser CI | [Deployment](deployment/README.md) |

## Current behavior

- Two players; up to eight rounds; 45-second preparation and at most 40 seconds of logical combat per round.
- Six recruitable unit types, three shop offers, five lane slots, and automatic three-copy merges up to level 3.
- Board capacity grows from three units to five in round 5. Locked formations survive between rounds; combat deaths do not delete owned units.
- The server commits a shared presentation schedule before animation. The worker advances only after it ends, without waiting for browser acknowledgments.
- Production and default Compose run API and worker jobs in one process. Separate API/worker deployment remains available.

## Verification in the repository

Backend tests exercise engine rules, authentication, planning, concurrency, recovery, and database transactions. Frontend tests cover commands, polling, reconstruction, animation, and round transitions. Playwright exercises two browser sessions against the real Compose stack through match completion. See [test commands](README.md#tests) and `.github/workflows/`.

The 15 numbered scenarios map to tests in `backend/engine/src/test/java/com/kingdom/engine/CombatEngineTest.java`. Test totals and CI status should be read from the current run rather than treated as a permanent project guarantee.

## Possible next steps

These are ideas, not shipped capabilities or committed release dates:

- Match history, a dedicated rematch flow, and participant replay browsing.
- Matchmaking and a rating model beyond the current fixed ±25 adjustment.
- Additional units, traits, and balance changes supported by simulation and playtesting.
- Measured improvements to polling, resource usage, and operational visibility.

The current build has no bots, spectator mode, public no-login replay, replay-export endpoint, or historical ruleset dispatcher. Any future rules changes need an explicit compatibility strategy for stored matches.

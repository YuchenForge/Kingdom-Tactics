# Architecture

Kingdom Tactics is a server-authoritative, two-player auto-battler. The browser sends planning commands and presents recorded combat; Java validates moves, resolves battles, and persists results in PostgreSQL.

## System overview

```text
React 19 + TypeScript + Vite
  TanStack Query polling · local selection/drag · React/CSS combat animation
                         │ HTTP locally / HTTPS in production
                         ▼
Spring Boot / Java 21 — combined API and worker process
  API: authentication, planning commands, state and result queries
  Worker: claim → simulate → commit → wait for presentation → advance
                         │
                         ▼
PostgreSQL 15 — Flyway migrations V1–V12
  users · games · game_players · rounds · round_plans
  shop_offers · commands · game_events · game_state_snapshots
```

Local Compose runs the frontend, combined backend, and database. Production uses Vercel for the frontend and a Render web service for the combined backend. The API and worker can also run separately. See [local setup](local-setup.md) and [deployment](../deployment/README.md).

## Code organization

| Location | Responsibility |
|---|---|
| `frontend/src/pages/` | Landing, authentication, invite/join, lobby, game, and results |
| `frontend/src/components/` | Planning controls, boards, unit details, and result feedback |
| `frontend/src/hooks/` | Authentication, polling, event loading, and presentation lifecycle |
| `frontend/src/combat/` | Pure event reconstruction, animation timing, and round transitions |
| `frontend/src/api/` | HTTP client, game endpoints, and recording assembly |
| `backend/engine/` | Combat domain, targeting, movement, planning validation, economy, and merging |
| `backend/api/` | HTTP/security, JPA entities, repositories, services, and Flyway migrations |
| `backend/worker/` | Round scanning and the three-transaction resolution pipeline |
| `backend/combined/` | Default application entry point, running API and worker together |

Java sources and tests use the standard `src/main/java` and `src/test/java` directories within each module. The combat core has no Spring, JPA, or HTTP dependency. The engine CLI uses Gson for JSON input/output.

## Planning commands

1. The JWT filter identifies the caller; the service verifies game membership and the current round.
2. Deadline checks finalize expired plans before accepting another action.
3. The engine validates and applies buy, sell, refresh, relocate, or lock.
4. The API persists the plan, command log, and shop changes transactionally.
5. The response includes an authoritative snapshot; the browser refreshes server state.

Each command requires an `Idempotency-Key` UUID. A retry in the supported preparation/locked phase returns the current committed snapshot without applying the action twice. A retry after resolution or advancement receives `409 WRONG_GAME_STATE`; the client refetches instead of resubmitting into another round.

Plans use optimistic versioning to detect conflicting updates. Manual locking and deadline finalization also acquire database locks to coordinate phase changes. Unique constraints protect command ordering and idempotency. State, event, and result reads use PostgreSQL `REPEATABLE READ` so each response comes from a consistent snapshot.

## Round resolution

The worker polls every 1 second by default (`app.worker.poll-ms`). Combat runs outside a database transaction.

| Step | Responsibility | Durable guard |
|---|---|---|
| TX 1 — claim | Claim a `LOCKED` round, persist its seed, set `RESOLVING` | `FOR UPDATE SKIP LOCKED` |
| Simulate | Merge locked formations and run deterministic combat | Same inputs and seed on retry |
| TX 2 — commit | Write events, end snapshots, Keep damage, outcome, and presentation times; set `ROUND_RESULT` | Row lock requiring `RESOLVING` |
| Presentation | Clients play recorded events against the persisted server schedule | No browser acknowledgment required |
| TX 3 — advance | Start the next preparation or finish the match and update ratings | Due presentation deadline and `advanced_at IS NULL` |

A crash after claim allows simulation to run again with the same seed. A crash after commit requires advancement only. Computation can repeat; committed damage, events, and advancement apply once. Historical round results remain available after the game advances.

The next round copies the locked formation, including units that died in combat, and grants 5 gold. Combat-end survivors are stored separately and never replace the next planning formation. Full HP is restored when boards merge for the next battle.

## Combat presentation

The frontend polls state every second in preparation and every 250 ms during `LOCKED`, `RESOLVING`, and `ROUND_RESULT`. Once preparation closes, `combatUnits` reveals both deployed formations; private shops and unused lane units remain hidden.

TX 2 stores one shared schedule: a 3-second lead-in, 250 ms per logical combat tick, then 1 second for final effects and 3 seconds for result feedback. TX 3 waits until that schedule ends, including the final round. The next preparation receives its full 45 seconds from actual advancement.

Clients load all event pages, reconstruct state from recorded events, and align animation to `serverTime`. Reloading joins the current timeline. An observed round retains its clock anchor and schedule while polling continues. Failed playback falls back to saved final snapshots. The browser does not calculate combat outcomes or delay server advancement.

## Determinism and storage

Combat uses integer stats, fixed ticks, and stable targeting/movement tie-breaks. The combat seed is persisted for replay; current combat does not consume randomness. Shop generation is seeded separately. Reproduction requires the same unit IDs, formations, rules, and definitions—not just the same seed.

There are three distinct representations:

- **Locked plan:** local placement coordinates and lane units; immutable combat input.
- **Combat-end snapshot:** surviving units in global coordinates and post-damage Keep HP.
- **Next-round plan:** a new copy of the locked formation with updated gold and a new shop.

See [board coordinates](board.md), [rules](rules.md), and the [database schema](database-schema.md). The schema records a rules version, but historical ruleset dispatch and a versioned replay export are not implemented.

## Security and operations

JWT authentication is stateless. Game reads and commands require participant access; joining is separately validated. Passwords are hashed with BCrypt. Production settings validate the signing secret and allowed HTTPS origins. Secrets belong in server environment settings, never `VITE_*` variables.

The combined process shares one database connection pool and uses two scheduler threads. Request logs include request IDs and game/user context when supplied. Public health endpoints expose status without database details:

| Endpoint | Check |
|---|---|
| `/health` | Aggregate health; used by local Compose |
| `/health/readiness` | Application readiness and PostgreSQL connectivity |
| `/health/liveness` | Application process state |

Readiness alone does not verify that rounds are progressing. A two-browser match exercises the worker as well as the API. There is no rate limiter, generated OpenAPI document, or automatic operations alerting integration.

## Verification

- Engine tests cover the [15 combat scenarios](combat-scenarios.md), planning, merging, and deterministic replay.
- API and worker Testcontainers tests cover authentication, privacy, concurrency, retries, transactions, and presentation deadlines.
- Frontend Vitest tests cover polling, commands, event reconstruction, and automatic presentation.
- Playwright runs two browser sessions against the real Compose stack through a completed match.

Commands and prerequisites are in the [root README](../README.md#tests). See the [API contract](api-contract.md) for exact response fields and error behavior.

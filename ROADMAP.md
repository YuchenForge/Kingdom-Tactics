# Roadmap

Kingdom Tactics is developed in 8 phases, each with a clear definition of "done."

---

## Phase 0: Design and rules specification ✅

**Status:** Complete

**Goal:** Remove ambiguity before implementation.

### Deliverables

- ✅ `docs/rules.md` — Complete, unambiguous rules
- ✅ `docs/state-machine.md` — State machine with all transitions
- ✅ `docs/combat-scenarios.md` — 15 written combat scenarios
- ✅ `docs/adr/001-server-authoritative-state.md`
- ✅ `docs/adr/002-event-log-and-snapshots.md`
- ✅ `docs/adr/003-deterministic-combat.md`
- ✅ `docs/architecture.md` — High-level system design

## Phase 1: Pure deterministic combat engine

**Status:** Complete ✅

**Goal:** Make the game correct before adding HTTP, a database, or UI.

### Build

**Domain models** (Java, no Spring/JPA/frameworks):
- `GameState` — Entire game snapshot
- `PlayerState` — One player's state
- `Board` — 4×4 placement board (local coordinates)
- `CombatBoard` — 4×8 merged combat board (P0 rotated 180°, P1 unchanged; global coordinates)
- `HoldingLane` — 5-slot holding lane for purchased units
- `Coordinates` — Local ↔ global coordinate mapping
- `UnitInstance` — An instantiated unit with HP, position, cooldown
- `UnitDefinition` — Unit stats (HP, ATK, RNG, special ability)
- `CombatEvent` — UNIT_MOVED, ATTACK, UNIT_DIED, HEALED, etc.
- `ResolutionResult` — Final board state, events, Keep damage

**Tick loop**:
- Seeded RNG (deterministic)
- 250ms logical tick (4 ticks/second)
- Cooldown management
- Target selection (with unit-specific tie-breaks)
- Movement (BFS pathfinding toward reachable attack positions)
- Attack and damage (max(1, ATK - armor))
- Death and removal

**Unit behaviors**:
- Squire: basic melee (no special)
- Shieldbearer: armor **1** (damage = max(1, ATK − armor))
- Ranger: target lowest HP
- Knight: target nearest enemy
- Mage: every 3rd **attack** = splash damage to orthogonal neighbors
- Healer: every 3rd **attack** = heal lowest HP ally by 5 (board-wide; no enemy in range required on heal turns)

**Rules**:
- Keep damage: 1 + survivor_count + floor(total_hp / 10)
- Deterministic tie-breaking: unit-specific sort order (varies by type), always ending in unit ID
- Time limit: 40 seconds = 160 ticks max

### Tests

Write 30+ unit tests. Each of the 15 combat scenarios becomes a test.

**Core test cases**:
1. Basic melee (Squire vs Squire)
2. Armor reduction (Knight vs Shieldbearer)
3. Range mechanics (Ranger, Knight, Mage)
4. Special abilities (Mage splash, Healer heal)
5. Tie-breaking (multiple units, same distance)
6. Time limit (40-second cap)
7. Empty board (immediate defeat)
8. Simultaneous deaths
9. Determinism (same seed → identical events)

**Test framework**: JUnit 5 + AssertJ + Mockito

### CLI harness

```bash
java -jar engine.jar --seed=12345 --board-json=board.json
```

Output:
```json
{
  "events": [
    { "type": "UNIT_MOVED", "unitId": "unit_001", "x": 0, "y": 2, "tick": 0 },
    { "type": "ATTACK", "attackerId": "unit_001", "targetId": "unit_002", "damage": 2, "tick": 4 },
    ...
  ],
  "finalBoard": [ ... ],
  "keepDamage": 3
}
```

### Definition of done

- 30+ unit tests pass
- All 15 combat scenarios pass
- CLI harness works
- Combat can be replayed from stored events with identical outcome
- Code has zero dependencies outside Java stdlib

---

## Phase 2: Backend foundation and persistence

**Status:** Complete

**Goal:** Turn the engine into a secure multi-user application.

### Build

**Spring Boot API**:
- REST endpoints (auth, games, state queries)
- Authentication (JWT tokens)
- Authorization (user owns game before allowing access)
- Request validation (Jakarta Bean Validation)
- Exception handling (standardized error responses)
- Logging with request IDs

**Database** (PostgreSQL + Flyway):
- `users`, `games`, `game_players`, `rounds`, `round_plans`
- Foreign key constraints
- Unique constraints (from schema spec)
- Migration scripts

**Docker Compose**:
- PostgreSQL container
- API via local `spring-boot:run` (container optional)

### Tests

- Unauthorized user cannot access game
- Only game participants can read private state
- Game cannot start with fewer than 2 players
- Player cannot join twice
- Schema migration test (Testcontainers)
- Auth/game edge and failure ITs (duplicates, expired JWT, WAITING state, etc.)

### API endpoints

```
POST /api/auth/register
POST /api/auth/login
GET  /api/me

POST /api/games
POST /api/games/{gameId}/join
GET  /api/games/{gameId}
GET  /api/games/{gameId}/state
```

### Definition of done

Two users can create and join a game and retrieve a valid PREPARATION-phase snapshot. No combat yet, just state management.

---

## Phase 3: Planning phase and command processing

**Status:** Complete ✅

**Goal:** Implement the auto-battler management loop.

### Build

**Deterministic shop generation**:
- Seeded random unit selection
- 3 offerings per round (may bump to 5 if playtests feel starved or the roster grows)
- Refresh costs 1 gold

**Command processing**:
- `BUY_UNIT(shopSlot)` — Check gold, deduct, add Level 1 unit to holding lane (max 5); run auto-merge when 3+ copies of same `(type, level)` exist on lane + board
- `SELL_UNIT(unitId)` — Remove, refund `base_cost × level`
- `REFRESH_SHOP()` — Generate new offers (1 gold cost)
- `RELOCATE_UNIT(unitId, to)` — Move unit to `Board(x,y)` or `Lane(slot)` (lane↔board, reorder, reposition)
- `LOCK_BOARD()` — Submit final plan

**Unit leveling** (see `docs/rules.md` §5):
- Levels 1–3; shop sells Level 1 only
- 3× same `(type, level)` across lane + board → auto-merge to `level + 1` (checked after each buy)
- Lane and board may each hold multiple copies until merge
- Stat scaling: HP-biased per-level tables (~+35% L2 / ~+85% L3 vs L1); Healer heal 5/7/10; range and Shieldbearer armor do not scale

**Command validation**:
- Gold checks (can afford?)
- Board bounds (x, y in 0..3)
- Unit ownership (is it your unit?)
- Capacity checks (not exceeding unit cap or lane size)

**Idempotency keys**:
- UUID in request header
- Prevents duplicate command effects
- Same command sent twice = second is silently ignored

### Tests

- Cannot buy without gold
- Cannot relocate outside board bounds or to an occupied lane slot
- Cannot exceed unit cap or lane capacity
- Auto-merge when 3+ copies of same `(type, level)` on lane + board (e.g. 2 on board + 3rd buy)
- Sell refund scales with level (`base_cost × level`)
- Duplicate request does not duplicate purchase
- Opponent cannot view shop or pending plans
- Locked board cannot be modified

### API endpoints

```
POST /api/games/{gameId}/rounds/{round}/buy
     Headers: Idempotency-Key: UUID
     Body: { shopSlot: 0-2 }

POST /api/games/{gameId}/rounds/{round}/refresh
POST /api/games/{gameId}/rounds/{round}/sell
POST /api/games/{gameId}/rounds/{round}/relocate
POST /api/games/{gameId}/rounds/{round}/lock
```

### Definition of done

Two players can independently build legal boards through API calls. Planning phase works end-to-end (lock both boards, transition to LOCKED state).

---

## Phase 4: Worker and round resolution

**Status:** ✅ Complete (optional full 8-round IT deferred; Compose worker shipped; backend GitHub Actions CI shipped; full Compose stack → Phase 7)

**Goal:** Resolve rounds reliably and commit each round’s resolution effects once (no duplicate events / Keep damage).

### Build

**Job transport:**
- **DB polling** every ~10s — three candidate sets:
  1. `LOCKED` (TX 1 claim)
  2. `RESOLVING` (retry engine + TX 2; same `combat_seed`)
  3. `ROUND_RESULT AND advanced_at IS NULL` (TX 3 only)

**Worker application** (separate Spring Boot / `backend/worker` module):
- **TX 1 — Claim:** `SELECT … FOR UPDATE SKIP LOCKED` → game+round `RESOLVING` + persist `combat_seed` → **commit**
- **Engine:** `CombatEngine.resolve` with **no** DB transaction held (may re-run after crash)
- **TX 2 — Resolve:** row-lock / re-read; require still `RESOLVING`; events + end snapshots + Keep HP → durable `ROUND_RESULT` (serializes commit)
- **TX 3 — Advance** (same job, new TX): `FINISHED` + ratings **or** next `PREPARATION` (gold +5, new shops); set `advanced_at`

**State transitions:**
- `LOCKED → RESOLVING → ROUND_RESULT → PREPARATION` (round < 8, no Keep KO)
- `LOCKED → RESOLVING → ROUND_RESULT → FINISHED` (Keep ≤ 0 after damage, or round 8 tie-break)

**Persistence:**
- Combat events (immutable, **1-based** `sequence_num` per game+round)
- Round-**end** snapshots only (survivors + Keep HP)
- `rounds.outcome` + `rounds.keep_damage` (TX 2; historical `/rounds/{n}/result`)
- `combat_seed` populated on claim (column already exists)
- Keep HP on `game_players` (already from Phase 3); draw → `games.winner_id` NULL

**API (api module, not worker):**
- `GET /api/games/{id}/events?round=N&afterSequence=M`
- `GET /api/games/{id}/rounds/{n}/result` (retrievable after advancement)
- `GET /api/games/{id}/result` (FINISHED; null winner on draw)
- `GET /state` exposes `RESOLVING` / `ROUND_RESULT` / `FINISHED` + `latestResolvedRound`

### Tests

| Checklist | Status | Where |
|---|---|---|
| Unit quartet (PlanBoardFactory, CombatSeedGenerator, MatchEndDecision, 1-based sequences) | ✅ | `PlanBoardFactoryTest`, `CombatSeedGeneratorTest`, `MatchEndDecisionTest`, `GameEventMapperTest` |
| Core TX ITs (claim / resolve / advance concurrency + idempotency) | ✅ | `ClaimServiceIT`, `ResolveServiceIT`, `MatchAdvancementServiceIT`, `RoundResolutionJobIT` |
| API read surface (events / round result / match result / state) | ✅ | `GameResultIT`, `GameResultServiceTest` |
| Crash after TX1 → path 2 (`retryResolving`) | ✅ | `RoundResolutionJobIT.retryResolving_recoversAfterCrashBetweenTx1AndTx2` |
| Crash after TX2 → path 3 (`advanceOnly`) | ✅ | `RoundResolutionJobIT.advanceOnly_recoversUnadvancedRoundResult` |
| Empty / mutual wipe | ✅ | `ResolutionServiceTest.resolve_emptyVsEmpty_isMutualWipeDraw` |
| Replay validation (plans + seed → engine ≈ TX2) | ✅ | `RoundResolutionJobIT.afterTx2_replayingPlansAndSeed_matchesPersistedOutcomeAndSurvivors` |
| Full 8-round match (DoD) | ⬜ | Optional / deferred — decision matrix + FINISH/draw ITs cover rules |
| Backend CI (`mvn -B test`, JDK 21, Testcontainers) | ✅ | `.github/workflows/backend.yml` |

Also present: `ClaimServiceTest`, `ResolveServiceTest` (incl. Keep HP clamp), `MatchAdvancementServiceTest`, `RoundScannerTest`, `RoundResolutionJobTest`, engine `UNIT_PLACED` enrichment tests.

Guides: [PHASE_4_GUIDE.md](backend/docs/PHASE_4_GUIDE.md), [PHASE_4_CHECKLIST.md](backend/docs/PHASE_4_CHECKLIST.md).

### Definition of done

Worker TX pipeline + Phase 4 read APIs are shipped. `ROUND_RESULT` is a durable checkpoint. Matches are replayable from stored plans + seed (+ events). Backend CI runs `mvn test` on push/PR. Compose already defines a worker service; full local stack (API + frontend + worker + Postgres) is Phase 7. Full 8-round loop IT remains optional.

---

## Phase 5: Frontend MVP

**Status:** Planned (after Phase 4)

**Goal:** Make the game playable without compromising backend correctness.

### Backend readiness (before Game UI)

Shipped for reload-safe planning UI:

- [x] Shared unit lookup (`yourUnits` on `/state`, `units` on commands): id, type, level, effective display stats
- [x] Shop offer display stats from server `UnitDefinition` (no client balance tables)
- [x] Reload IT for placed + auto-merged board units

Details: [api-contract.md](docs/api-contract.md).

### Build

**React + Vite setup**:
- `npm create vite@latest -- --template react-ts`
- Configure Tailwind CSS
- Setup TanStack Query for server-state management
- Local UI via React `useState` (selected/drag)

**Core pages**:
- Landing page (rules, login)
- Auth page (register, login)
- Lobby page (create game, join by invite link)
- Game board page (main game UI)
- Result page (match outcome; play again via create/join)

**Game board component**:
- 4×4 CSS Grid placement board per player (local coordinates)
- Shop display (3 unit cards) with server display stats
- 5-slot holding lane per player
- Gold and Keep HP counters
- "Lock board" button
- `yourBoard` IDs + `yourUnits` lookup (backend readiness above)

**State polling**:
- TanStack Query polling ~**1s** through PREPARATION / LOCKED / RESOLVING / ROUND_RESULT
- “Resolving…” then static round/match result APIs
- Automatic refetch on action completion

**Loading/error states**:
- Spinner during API requests
- Error toast on failures
- Reconnect / refetch on wrong-state

### Tests

- Vitest + React Testing Library
- Happy path: create game, place units, lock, see static result
- Error handling: buy without gold; relocate does not cost gold

### Definition of done

Two people can play a complete 8-round match in separate browser sessions. Fully playable with static results (replay animation is Phase 6).

---

## Phase 6: Combat replay and animation

**Status:** Planned (after Phase 5)

**Goal:** Make outcomes understandable and visually satisfying.

### Build

**Event fetch** (Phase 4 APIs):
- `GET /api/games/{gameId}/events?round=N&afterSequence=M&limit=…`
- Page until `complete && !hasMore` after a resolved round is discovered
- **No** separate `/replay` aggregation endpoint for MVP

**Animation** (Framer Motion + local state):
- Order events by sequence → group by tick → animate tick → next tick (~250ms)
- Get MOVE / ATTACK / DAMAGE / DEATH correct before any overlap scheduler

**Combat replay controls**:
- Play / Pause / 1× / 2× / Restart
- Optional in-match overlay during next planning (dismissible; never blocks opponent)

**Animations**:
- Unit placement, movement, attack, heal, death, Keep damage banner

### Tests

- Playwright: watch full combat replay; positions match server end snapshot

### Definition of done

A player can watch a completed round with clear tick-sequential animations and basic controls. Final board matches server combat-end snapshot.

---

## Phase 7: Quality, deployment, and portfolio polish

**Status:** Planned (after Phase 6)

**Goal:** Make it credible as a production-style project.

### Build

**Docker Compose** (local full stack):
- Frontend, API, worker, PostgreSQL  
  (worker service already in `docker-compose.yml` from Phase 4; Phase 7 adds API + frontend into one compose path)

**GitHub Actions CI**:
- Backend tests (`*Test` + `*IT`)
- Frontend lint/typecheck/unit
- Playwright replay gate
- Build artifacts

**Production deployment**:
- Vercel: frontend
- Render: API + worker + PostgreSQL
- Production env vars, CORS, HTTPS

**Required observability**:
- `/health` (or platform health) + DB-backed readiness for deploys

**Documentation**:
- README with screenshots/GIF + live URL
- Architecture diagram
- Local setup + deployment notes

**Optional polish:** structured JSON logs, separate `/ready`, Swagger export, OpenTelemetry, dependency scanning, public no-login demo/replay (only if cheap)

### Definition of done

Someone can open the live URL, create an account, play (invite a friend), watch participant replay, clone + Docker Compose, and see green CI — without help from you. A public spectator/demo is optional.

### Tests

- Playwright end-to-end happy path (create game, play 2 rounds, verify result)
- Integration test with all services (docker-compose up, run test, docker-compose down)

---

## Phase 8: Choose one expansion

**Status:** Planned (after Phase 7)

Pick **one** expansion. This demonstrates ability to extend a system without breaking existing code.

### Options

| Option | What it demonstrates | Effort |
|--------|----------------------|--------|
| **WebSockets** | Real-time delivery, reconnect/catch-up, stateful server connections | Medium |
| **Traits/synergies** | Extensible rules engine, composition patterns, data-driven design | Medium |
| **Matchmaking + ELO** | Queues, ranking systems, aggregation, background job orchestration | Medium-High |
| **Spectator mode** | Public/private state boundaries, permission systems, live feeds | Medium |
| **Admin dashboard** | Operational tooling, analytics, filters, exports, data inspection | Medium-High |
| **Bot API** | Public API design, rate limits, API docs, simulation, external integrations | Medium |
| **OAuth + email invites** | Third-party authentication, async email, invitation flows | Low-Medium |

---

## Timeline

Assumes 2-3 weeks per phase if working full-time:

| Phase | Duration | Total |
|-------|----------|-------|
| 0 (Design) | 1 week | 1 week |
| 1 (Engine) | 2 weeks | 3 weeks |
| 2 (Backend) | 2 weeks | 5 weeks |
| 3 (Commands) | 2 weeks | 7 weeks |
| 4 (Worker) | 2 weeks | 9 weeks |
| 5 (Frontend) | 2 weeks | 11 weeks |
| 6 (Replay) | 1 week | 12 weeks |
| 7 (Deploy) | 1 week | 13 weeks |
| 8 (Expansion) | 2-3 weeks | 16 weeks |

**Total:** ~4 months for a complete, production-ready project.

If working part-time, double or triple each estimate.

---

## Success criteria

For **hiring purposes**, this project demonstrates:

1. **Full-stack competence**
   - Frontend (React, state management, real-time UI)
   - Backend (Spring Boot, databases, transactions)
   - DevOps (Docker, CI/CD, deployment)

2. **System design**
   - Clear separation of concerns (engine, API, worker)
   - Determinism and replay for auditability
   - Thoughtful architecture decisions (ADRs)

3. **Reliability**
   - Comprehensive testing (unit, integration, E2E)
   - Error handling and edge cases
   - Observability (logging, health checks)

4. **Production mindset**
   - Idempotency and transaction safety
   - Proper concurrency handling
   - Deployment and scaling considerations

5. **Communication**
   - Clear documentation
   - Thoughtful naming and code organization
   - Ability to explain design tradeoffs

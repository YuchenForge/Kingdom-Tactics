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

### Definition of done

You can hand the rules document and architecture guide to another developer, and they can implement the engine without asking clarifying questions.

---

## Phase 1: Pure deterministic combat engine

**Status:** Next

**Goal:** Make the game correct before adding HTTP, a database, or UI.

### Build

**Domain models** (Java, no Spring/JPA/frameworks):
- `GameState` — Entire game snapshot
- `PlayerState` — One player's state
- `Board` — 4×4 placement board (local coordinates)
- `CombatBoard` — 4×8 merged combat board (global coordinates, stacked vertically)
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
- Movement (greedy pathfinding toward nearest enemy)
- Attack and damage (max(1, ATK - armor))
- Death and removal

**Unit behaviors**:
- Squire: basic melee (no special)
- Shieldbearer: −1 armor (minimum 1 damage)
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

**Status:** Planned (after Phase 1)

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
- API container
- PostgreSQL container
- Redis container (for later phases)

### Tests

- Unauthorized user cannot access game
- Only game participants can read private state
- Game cannot start with fewer than 2 players
- Player cannot join twice
- Schema migration test (Testcontainers)

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

**Status:** Planned (after Phase 2)

**Goal:** Implement the auto-battler management loop.

### Build

**Deterministic shop generation**:
- Seeded random unit selection
- 5 offerings per round
- Refresh costs 1 gold

**Command processing**:
- `BUY_UNIT(shopSlot)` — Check gold, deduct, add to holding lane (max 5)
- `SELL_UNIT(unitId)` — Remove, refund gold
- `REFRESH_SHOP()` — Generate new offers (1 gold cost)
- `PLACE_UNIT(unitId, x, y)` — Move from lane to placement board (any empty cell, local coords)
- `MOVE_UNIT(unitId, x, y)` — Move on placement board (any empty cell, local coords)
- `LOCK_BOARD()` — Submit final plan

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
- Cannot place outside board bounds
- Cannot exceed unit cap or lane capacity
- Duplicate request does not duplicate purchase
- Opponent cannot view shop or pending plans
- Locked board cannot be modified

### API endpoints

```
POST /api/games/{gameId}/rounds/{round}/buy
     Headers: Idempotency-Key: UUID
     Body: { shopSlot: 0-4 }

POST /api/games/{gameId}/rounds/{round}/refresh
POST /api/games/{gameId}/rounds/{round}/sell
POST /api/games/{gameId}/rounds/{round}/place
POST /api/games/{gameId}/rounds/{round}/move
POST /api/games/{gameId}/rounds/{round}/lock
```

### Definition of done

Two players can independently build legal boards through API calls. Planning phase works end-to-end (lock both boards, transition to LOCKED state).

---

## Phase 4: Worker and round resolution

**Status:** Planned (after Phase 3)

**Goal:** Resolve rounds reliably and exactly once.

### Build

**Redis setup**:
- Job queue for round resolutions
- Rate limiting (later)
- Caching (later)

**Worker application** (separate Spring Boot process):
- Polls for locked rounds every 10 seconds
- Acquires row-level lock (`SELECT ... FOR UPDATE`)
- Calls combat engine
- Persists events and snapshots
- Transitions round state to ROUND_RESULT
- Releases lock

**State transitions**:
- LOCKED → RESOLVING (worker picks up)
- RESOLVING → ROUND_RESULT (resolution complete)
- ROUND_RESULT → PREPARATION (if round < 8 and no winner)
- ROUND_RESULT → FINISHED (if winner or round 8)

**Persistence**:
- Combat events (sequence-numbered, immutable once written)
- Round-end snapshot (board, Keep damage, etc.)
- Rules version (for future migrations)

### Tests

- Two workers cannot resolve the same round twice
- Retry after failure does not duplicate events
- Empty plan works (0 units on board → immediate loss)
- Snapshot matches event log (replay validation)
- Full 8-round match resolves correctly

### Definition of done

A complete 8-round match can run via API with worker resolution. Full match is replayable from stored events.

---

## Phase 5: Frontend MVP

**Status:** Planned (after Phase 4)

**Goal:** Make the game playable without compromising backend correctness.

### Build

**React + Vite setup**:
- `npm create vite@latest -- --template react-ts`
- Configure Tailwind CSS
- Setup TanStack Query for server-state management
- Setup Zustand for local UI state

**Core pages**:
- Landing page (rules, login, demo replay)
- Auth page (register, login)
- Lobby page (create game, join by invite)
- Game board page (main game UI)
- Result page (match outcome, rematch)
- Replay page (step through events)

**Game board component**:
- 4×4 CSS Grid placement board per player (local coordinates)
- Shop display (5 unit cards)
- 5-slot holding lane per player
- 4×8 merged combat board for replay (global coordinates, stacked vertically)
- Gold and Keep HP counters
- "Lock board" button
- Action confirmation (buy, place, sell)

**State polling**:
- TanStack Query polling every 500ms during planning
- TanStack Query polling every 500ms during combat
- Automatic refetch on action completion

**Loading/error states**:
- Spinner during API requests
- Error toast on failures
- Reconnect logic

### Tests

- Vitest + React Testing Library
- Happy path: create game, place units, lock, watch combat
- Error handling: cannot place unit without gold, cannot exceed unit cap

### Definition of done

Two people can play a complete 8-round match in separate browser sessions. Game is fully playable (no replay yet, just real-time results).

---

## Phase 6: Combat replay and animation

**Status:** Planned (after Phase 5)

**Goal:** Make outcomes understandable and visually satisfying.

### Build

**Event streaming**:
- Endpoint: `GET /api/games/{gameId}/events?afterSequence=42`
- Returns events since last fetch (polling-based, not WebSocket yet)

**Animation engine** (Zustand + Framer Motion):
- Queue of animations (unit moved, unit attacked, unit died, Keep damaged)
- Each animation: 200-400ms
- Sequential playback

**Combat replay controls**:
- Play / Pause buttons
- Step forward / backward (by event)
- Playback speed (0.5×, 1×, 2×)
- Jump to event

**Animations**:
- Unit placement (grid → grid)
- Unit movement (slide across board)
- Attack lunge (unit leans forward, back)
- Damage number (flying text, fade out)
- Death (unit fades, removed from board)
- Keep damage (counter updates with +X)

### Tests

- Playwright: create game, watch full combat replay, verify unit positions match server state

### Definition of done

A player can watch a completed round with smooth animations and replay it frame-by-frame. Final board state matches server snapshot.

---

## Phase 7: Quality, deployment, and portfolio polish

**Status:** Planned (after Phase 6)

**Goal:** Make it credible as a production-style project.

### Build

**Docker Compose** (local development):
- Frontend container (Vite dev server)
- API container (Spring Boot)
- Worker container (Spring Boot worker)
- PostgreSQL container
- Redis container
- All interconnected and ready to go

**GitHub Actions CI/CD**:
- Unit tests (backend + frontend)
- Integration tests (Testcontainers)
- Linting + type checking
- Build artifacts
- E2E tests (Playwright)

**Production deployment**:
- Vercel: frontend (auto-deploy on push to main)
- Render: API service + worker service + PostgreSQL + Redis
- Automatic scaling (if applicable)

**Logging and observability**:
- Structured logs (JSON, requestId, gameId, userId)
- `/health` endpoint
- `/ready` endpoint for load balancer
- Request tracing (OpenTelemetry, optional)

**Documentation**:
- README with screenshots/GIF
- Architecture diagrams (rendered from this file)
- Local setup guide
- API docs (Swagger UI)
- Deployment instructions

**Demo**:
- Seeded demo match (deterministic replay)
- Public replay viewer (no login required)

### Tests

- Playwright end-to-end happy path (create game, play 2 rounds, verify result)
- Integration test with all services (docker-compose up, run test, docker-compose down)

### Definition of done

Someone can:
1. Open a deployed URL (no local setup)
2. Create an account
3. Create a game and invite a friend
4. Play the game
5. Watch a replay
6. Verify the architecture and code quality

Without needing any help from you.

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

### Recommendation for SWE hiring

**Admin dashboard** or **WebSockets** demonstrates the most value:
- **Admin dashboard:** Shows you can build operational tooling, query complex data, and design effective UIs for non-players.
- **WebSockets:** Shows you can handle real-time state delivery, connection management, and complex concurrency.

Both extend the core system without breaking it and add substantial complexity.

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

# Architecture

## System overview

Kingdom Tactics is a three-tier system: React frontend, Java backend, and background worker process.

```
┌─────────────────────────────────────────────────────────────────┐
│                      React + TypeScript                         │
│                   (Client-side UI state)                        │
│                                                                 │
│  Tailwind CSS Grid board                                        │
│  TanStack Query (server-state polling)                          │
│  Zustand (selected unit, animation queue, replay controls)      │
│  Framer Motion (placement, combat, effects)                     │
└─────────────────────────────────┬───────────────────────────────┘
                                  │ HTTPS
                                  │
                    ┌─────────────▼─────────────┐
                    │   Spring Boot API         │
                    │   (Server-authoritative)  │
                    │                           │
                    │ Authentication            │
                    │ Game state queries        │
                    │ Command processing        │
                    │ Authorization checks      │
                    │ Request validation        │
                    └──────┬──────┬──────┬──────┘
                           │      │      │
            ┌──────────────┘      │      └──────────────┬──────────┐
            │                     │                     │          │
            ▼                     ▼                     ▼          ▼
       PostgreSQL              Redis                Worker       (Cache layer)
       (Persistent)         (Ephemeral)           (Background)
            │                     │                     │
            ├─ users              ├─ job queue          ├─ Pick up locked rounds
            ├─ games              ├─ rate limiting      ├─ Run combat engine
            ├─ game_players       ├─ sessions           ├─ Persist events/snapshots
            ├─ rounds             └─ caching (later)    └─ Publish updates
            ├─ commands
            ├─ game_events
            ├─ game_state_snapshots
            └─ ratings
```

---

## Frontend architecture

### Tech stack

- **React 18** — Component-based UI
- **TypeScript** — Type-safe code
- **Vite** — Fast build tool
- **Tailwind CSS** — Utility-first styling
- **CSS Grid** — Board layout
- **Framer Motion** — Placement and combat animations
- **TanStack Query** — Server-state fetching and caching
- **Zustand** — Client UI state (selected unit, replay controls)

### Folder structure

```
frontend/
├── src/
│   ├── components/
│   │   ├── Board.tsx          # 4×4 grid + unit rendering
│   │   ├── Shop.tsx           # Unit offers
│   │   ├── Bench.tsx          # Off-board units
│   │   ├── UnitCard.tsx       # Individual unit display
│   │   ├── HealthBar.tsx      # HP visualization
│   │   └── CombatReplay.tsx   # Event playback controls
│   ├── pages/
│   │   ├── LandingPage.tsx
│   │   ├── AuthPage.tsx
│   │   ├── LobbyPage.tsx
│   │   ├── GameBoardPage.tsx  # Main game UI
│   │   ├── ResultPage.tsx
│   │   └── ReplayPage.tsx
│   ├── hooks/
│   │   ├── useGame.ts         # TanStack Query hook for game state
│   │   ├── useGameState.ts    # Game state polling/refetch
│   │   └── useAnimation.ts    # Framer Motion helpers
│   ├── store/
│   │   └── gameStore.ts       # Zustand: selected unit, animations, replay
│   ├── api/
│   │   ├── client.ts          # Fetch wrapper, auth header injection
│   │   └── gameApi.ts         # Game endpoints (fetch, poll)
│   ├── types/
│   │   └── index.ts           # Shared types with backend
│   ├── App.tsx
│   └── main.tsx
├── public/
└── vite.config.ts
```

### State management

**Server state (TanStack Query):**
- Current game state (board, units, gold, Keep HP)
- Locked plans (visible only to self)
- Combat events
- Match history

**Client UI state (Zustand):**
- Selected unit (for placement dragging)
- Animation queue (which unit is moving, where)
- Replay playhead (which tick to display)
- UI modals (settings, help, disconnect)

### Key flows

**Planning phase:**
1. Client fetches current game state via TanStack Query
2. User clicks to buy/place/move units
3. Each command sent to backend with Idempotency-Key header
4. Backend responds with new state (gold, board, etc.)
5. TanStack Query refetches and updates cache
6. UI re-renders from cached state

**Combat phase:**
1. Client polls `/api/games/{gameId}/events` every 500ms
2. Backend returns new combat events since last fetch
3. Zustand animation queue processes events sequentially
4. Each event triggers Framer Motion animation
5. User watches units move, attack, die in real-time

---

## Backend architecture

### Tech stack

- **Java 21** — Language
- **Spring Boot 3** — Web framework
- **Spring Data JPA + Hibernate** — ORM
- **PostgreSQL 15+** — Persistence
- **Redis 7+** — Caching, job queue
- **Flyway** — Database migrations
- **Jakarta Bean Validation** — Request validation
- **OpenAPI/Swagger** — API documentation

### Module structure

The backend is split into 4 modules to enforce clean architecture:

```
backend/
├── engine/
│   ├── main/java/com/kingdom/engine/
│   │   ├── domain/
│   │   │   ├── GameState.java
│   │   │   ├── PlayerState.java
│   │   │   ├── Board.java
│   │   │   ├── UnitInstance.java
│   │   │   ├── UnitDefinition.java
│   │   │   ├── CombatEvent.java
│   │   │   └── ResolutionResult.java
│   │   ├── service/
│   │   │   ├── CombatEngine.java       # Tick loop, target selection, damage
│   │   │   ├── TargetSelector.java
│   │   │   └── UnitBehavior.java       # Per-unit actions
│   │   └── util/
│   │       └── DeterministicRandom.java
│   └── test/java/...
│
├── shared/
│   ├── main/java/com/kingdom/shared/
│   │   ├── dto/
│   │   │   ├── GameStateDto.java
│   │   │   ├── CommandDto.java
│   │   │   └── EventDto.java
│   │   ├── event/
│   │   │   ├── UnitMovedEvent.java
│   │   │   ├── AttackEvent.java
│   │   │   └── UnitDiedEvent.java
│   │   └── constants/
│   │       ├── UnitTypes.java
│   │       └── GameRules.java
│   └── test/java/...
│
├── api/
│   ├── main/java/com/kingdom/api/
│   │   ├── controller/
│   │   │   ├── AuthController.java
│   │   │   ├── GameController.java
│   │   │   ├── CommandController.java
│   │   │   └── ReplayController.java
│   │   ├── service/
│   │   │   ├── GameService.java        # Orchestrate game state
│   │   │   ├── CommandService.java     # Validate & apply commands
│   │   │   └── AuthService.java
│   │   ├── config/
│   │   │   ├── SecurityConfig.java
│   │   │   ├── JpaConfig.java
│   │   │   └── RedisConfig.java
│   │   ├── entity/
│   │   │   ├── User.java
│   │   │   ├── Game.java
│   │   │   ├── Round.java
│   │   │   ├── Command.java
│   │   │   ├── GameEvent.java
│   │   │   └── GameStateSnapshot.java
│   │   ├── repository/
│   │   │   ├── GameRepository.java
│   │   │   ├── RoundRepository.java
│   │   │   └── GameEventRepository.java
│   │   ├── exception/
│   │   │   ├── GameNotFoundException.java
│   │   │   ├── InvalidCommandException.java
│   │   │   └── UnauthorizedException.java
│   │   └── filter/
│   │       └── RequestIdFilter.java    # Trace all requests
│   └── test/java/...
│
└── worker/
    ├── main/java/com/kingdom/worker/
    │   ├── job/
    │   │   ├── RoundResolutionJob.java
    │   │   └── RoundScanner.java       # Find locked rounds
    │   ├── service/
    │   │   ├── ResolutionService.java  # Orchestrate resolution
    │   │   ├── EventPersistence.java
    │   │   └── SnapshotService.java
    │   ├── config/
    │   │   └── WorkerConfig.java
    │   └── WorkerApplication.java
    └── test/java/...
```

### Key principle: Engine independence

The `engine` module has **zero dependencies** on:
- Spring
- JPA/Hibernate
- HTTP frameworks
- Logging frameworks
- Configuration libraries

This allows:
- Pure unit tests (fast, no mocks)
- Offline replay validation
- Reusable in multiple codebases (CLI, desktop, mobile)
- Clear separation of concerns

### Data flow: Planning phase

```
Client (HTTP POST /api/games/{gameId}/rounds/{round}/place)
         │
         ├─ Header: Idempotency-Key: UUID
         └─ Body: { unitId, x, y }
         │
         ▼
AuthController
         │
         ├─ Validate auth token
         ├─ Check user owns game
         └─ Extract user_id
         │
         ▼
CommandController.place()
         │
         ├─ Validate request (x,y in bounds, etc.)
         ├─ Check idempotency key (prevent dupes)
         └─ Call CommandService
         │
         ▼
CommandService.place()
         │
         ├─ Load game from DB
         ├─ Load round from DB
         ├─ Load round_plan from DB
         ├─ Validate: unit on bench, destination empty, gold available
         ├─ Apply command (move unit from bench to board)
         ├─ Persist round_plan & command to DB
         ├─ Save idempotency key result
         └─ Return updated board state
         │
         ▼
Client receives { board, bench, gold, ... }
```

### Data flow: Resolution phase (worker)

```
Worker (background process, polls every 10 seconds)
         │
         ├─ Find all rounds in LOCKED state
         ├─ Attempt to acquire row-level lock (SELECT ... FOR UPDATE)
         │
         ▼ (if lock acquired)
         │
ResolutionService.resolve()
         │
         ├─ Load round + both player plans
         ├─ Load unit definitions (from rules_version)
         ├─ Create CombatEngine with seed
         ├─ Call engine.resolve(board_state, seed)
         │
         ▼
CombatEngine.resolve()
         │
         ├─ Initialize tick loop
         ├─ For each tick (0..160):
         │  ├─ Decrement all cooldowns
         │  ├─ For each unit (sorted by ID):
         │  │  ├─ Select target (TargetSelector)
         │  │  ├─ If in range: attack (apply damage)
         │  │  ├─ Else: move 1 tile toward enemy
         │  │  └─ Record event
         │  ├─ Remove dead units
         │  └─ Check end condition
         │
         ├─ Calculate Keep damage
         ├─ Return ResolutionResult
         │
         ▼
EventPersistence.persist()
         │
         ├─ Write combat_events (UNIT_MOVED, ATTACK, UNIT_DIED, etc.)
         ├─ Write round_end_snapshot
         ├─ Transition round to ROUND_RESULT
         ├─ Release row-level lock
         └─ Publish event: ROUND_RESOLVED
         │
         ▼
Client polls /api/games/{gameId}/events
         │
         └─ Receives new combat events, triggers animations
```

---

## Database schema

### Core tables

**users**
```sql
id              UUID PK
username        VARCHAR(50) UNIQUE
email           VARCHAR(100) UNIQUE
password_hash   BYTEA
rating          INT DEFAULT 1200
created_at      TIMESTAMP
updated_at      TIMESTAMP
```

**games**
```sql
id              UUID PK
player_1_id     UUID FK(users)
player_2_id     UUID FK(users)
state           VARCHAR (WAITING_FOR_PLAYERS, PREPARATION, LOCKED, RESOLVING, ROUND_RESULT, FINISHED)
current_round   INT
winner_id       UUID FK(users) NULLABLE
started_at      TIMESTAMP
finished_at     TIMESTAMP NULLABLE
created_at      TIMESTAMP

UNIQUE(player_1_id, player_2_id, started_at)
```

**rounds**
```sql
id                  UUID PK
game_id             UUID FK(games)
round_number        INT
state               VARCHAR (PREPARATION, LOCKED, RESOLVING, ROUND_RESULT)
rules_version       VARCHAR DEFAULT '1.0'
combat_seed         BIGINT NULLABLE
planning_deadline   TIMESTAMP
started_at          TIMESTAMP
finished_at         TIMESTAMP NULLABLE

UNIQUE(game_id, round_number)
```

**round_plans**
```sql
id                UUID PK
round_id          UUID FK(rounds)
player_id         UUID FK(users)
is_locked         BOOLEAN DEFAULT FALSE
board_state       JSONB       -- { unit_id: {x, y}, ... }
bench_units       JSONB       -- [ unit_id, ... ]
gold              INT
locked_at         TIMESTAMP NULLABLE

UNIQUE(round_id, player_id)
UNIQUE(player_id, idempotency_key)  -- Prevent duplicate buys
```

**commands**
```sql
id                UUID PK
round_plan_id     UUID FK(round_plans)
sequence_number   INT
command_type      VARCHAR (BUY_UNIT, SELL_UNIT, PLACE_UNIT, MOVE_UNIT, REFRESH_SHOP, LOCK_BOARD)
parameters        JSONB       -- { shopSlot: 2 } or { unitId: UUID, x: 0, y: 1 }
executed_at       TIMESTAMP

UNIQUE(round_plan_id, sequence_number)
```

**game_events**
```sql
id              UUID PK
game_id         UUID FK(games)
round_number    INT
sequence_num    INT
event_type      VARCHAR (UNIT_MOVED, ATTACK, UNIT_DIED, HEALED, COMBAT_ENDED, etc.)
data            JSONB       -- { unitId, x, y, damage, ... }
tick            INT
created_at      TIMESTAMP

UNIQUE(game_id, round_number, sequence_num)
```

**game_state_snapshots**
```sql
id              UUID PK
game_id         UUID FK(games)
round_number    INT
is_round_start  BOOLEAN     -- true if round_start, false if round_end
player_id       UUID FK(users)
keep_hp         INT
gold            INT
board           JSONB       -- { 0: UUID, 1: null, 2: UUID, ... }
bench           JSONB       -- [ UUID, UUID ]
shop            JSONB       -- [ { name, cost, hp, ... }, ... ]
created_at      TIMESTAMP

UNIQUE(game_id, round_number, is_round_start, player_id)
```

---

## API contract

### Auth endpoints

```
POST /api/auth/register
  Request:  { username, email, password }
  Response: 201 { userId, token }

POST /api/auth/login
  Request:  { email, password }
  Response: 200 { userId, token }

GET /api/me
  Headers:  Authorization: Bearer {token}
  Response: 200 { userId, username, rating, createdAt }
```

### Game endpoints

```
POST /api/games
  Request:  { }
  Response: 201 { gameId, state: WAITING_FOR_PLAYERS }

POST /api/games/{gameId}/join
  Request:  { }
  Response: 200 { gameId, state: PREPARATION, round: 1 }

GET /api/games/{gameId}
  Response: 200 { gameId, state, round, players: [ ... ], ... }

GET /api/games/{gameId}/state
  Response: 200 { keep_hp, gold, board, bench, shop, lockedPlans: {...} }
```

### Planning endpoints

```
POST /api/games/{gameId}/rounds/{round}/buy
  Headers:  Idempotency-Key: UUID
  Request:  { shopSlot: 0-4 }
  Response: 200 { gold, board, bench }

POST /api/games/{gameId}/rounds/{round}/place
  Headers:  Idempotency-Key: UUID
  Request:  { unitId, x, y }
  Response: 200 { board, bench }

POST /api/games/{gameId}/rounds/{round}/lock
  Headers:  Idempotency-Key: UUID
  Request:  { }
  Response: 200 { locked: true }
```

### Event endpoints

```
GET /api/games/{gameId}/events?afterSequence=42
  Response: 200 { events: [ { type, data, tick }, ... ] }

GET /api/games/{gameId}/replay
  Response: 200 { rounds: [ { startSnapshot, commands, events, endSnapshot }, ... ] }
```

---

## Concurrency model

### Planning phase

- **No locking.** Players issue commands concurrently.
- **Conflict resolution:** Last write wins for PLACE/MOVE.
- **Idempotency keys** prevent duplicate effects (same BUY twice = one unit + one gold charge).
- **Optimistic concurrency:** If two players move the same unit simultaneously, the first one wins; second gets rejected with "unit already placed."

### Resolution phase

- **Row-level locking** on the round (PostgreSQL `SELECT ... FOR UPDATE`).
- Only one worker can hold the lock and resolve the round.
- On success, lock is released and state transitions to ROUND_RESULT.
- On failure, lock is released and round is re-queued for retry.

### Watch for

- **Idempotency key collisions:** If a player sends the same request twice within 1 second, the key should match and both requests return the same result (deduped).
- **Stale reads:** Client caches stale board state; backend returns fresh state after every command to force UI refresh.
- **Race conditions on unit placement:** Two commands placing different units at same position → one succeeds, other rejected.

---

## Deployment topology

### Local development (Docker Compose)

```
frontend (localhost:5173)
api (localhost:8080)
postgres (localhost:5432)
redis (localhost:6379)
worker (background)
```

### Production (e.g., Render + Vercel)

```
Vercel
  └─ frontend (React SPA)
         │
         ├─ HTTPS
         └─ API base: https://api.kingdom-tactics.com

Render
  ├─ API service (Spring Boot)
  │  ├─ health: /health
  │  └─ readiness: /ready (checks DB + Redis)
  │
  ├─ PostgreSQL (managed)
  │
  ├─ Redis (managed, for job queue)
  │
  └─ Worker service (same JAR, different entrypoint)
     └─ Polls rounds every 10 seconds

Sentry (error tracking, later phase)
```

---

## Observability

### Logging

Every request gets a `requestId` (UUID). All logs include `requestId`, `gameId` (if applicable), `userId`, and timestamp.

Example log:
```
2024-01-15 14:23:45 INFO  [req:abc123] user:user_456 game:game_789 action:PLACE_UNIT unit:unit_001 x:0 y:1 status:SUCCESS
```

### Monitoring

Health check endpoints:

```
GET /health
  → { status: UP, database: UP, redis: UP }

GET /ready
  → { ready: true, pending_rounds: 3 }

GET /health/games
  → { total_active: 42, by_state: { PREPARATION: 15, LOCKED: 8, ... } }
```

### Debugging

All matches can be replayed:

```
GET /api/games/{gameId}/replay
  → { rounds: [ { rules_version, seed, startSnapshot, commands, events, endSnapshot }, ... ] }
```

Use this to:
- Verify combat results
- Reproduce bugs
- Validate rule changes

---

## Security considerations

1. **Authentication:** JWT tokens in Authorization header. Stateless (no session storage).
2. **Authorization:** Every endpoint checks user owns the game before returning data.
3. **Rate limiting:** Redis-backed rate limiter (100 requests/minute per user).
4. **Input validation:** Jakarta Bean Validation + custom validators on all command endpoints.
5. **HTTPS only:** Prod enforces HTTPS; no plaintext transmission.
6. **CORS:** Frontend origin whitelisted on /api endpoints.
7. **Server-authoritative:** All combat outcomes computed server-side; client cannot forge results.

---

## Testing strategy

### Unit tests (engine module, pure Java)

- Test each unit's behavior (damage, healing, targeting)
- Test combat scenarios (from `docs/combat-scenarios.md`)
- Verify determinism: same seed → same events

Example:
```java
@Test
void ranger_targets_lowest_hp_unit() {
  Board board = setupBoard(ranger, squire1_hp8, squire2_hp6);
  CombatEngine engine = new CombatEngine(seed);
  ResolutionResult result = engine.resolve(board, seed);
  
  assertThat(result.events.get(0).target).isEqualTo(squire2);  // HP=6 < HP=8
}
```

### Integration tests (api module, with Testcontainers)

- Test end-to-end game flow: create game, place units, lock, resolve
- Verify database state after each step
- Test error paths (invalid commands, unauthorized access)

Example:
```java
@Test
@WithPostgresContainer
void two_players_can_play_full_round() {
  Game game = createGame(player1, player2);
  placeUnits(game, player1, units1);
  placeUnits(game, player2, units2);
  lockBoard(game, player1);
  lockBoard(game, player2);
  
  // Wait for worker to resolve
  Thread.sleep(2000);
  
  Round round = getRound(game, 1);
  assertThat(round.state).isEqualTo(ROUND_RESULT);
  assertThat(round.events).isNotEmpty();
}
```

### E2E tests (frontend, Playwright)

- User can create game, join, place units, lock, watch replay
- UI updates in real-time
- Errors display gracefully

---

## Performance considerations

1. **Database:** Indexes on (game_id, round_number), (player_id, state), created_at.
2. **Caching:** Redis for user sessions, rate limit counters, and (later) game state caches.
3. **Polling:** Frontend polls `/events` every 500ms during combat. OK for 8 rounds. Later phases may use WebSockets.
4. **Worker parallelism:** Multiple worker instances can resolve different rounds in parallel (row-level locking ensures no conflicts).

---

## Future extensions (Phase 8+)

- **WebSockets:** Real-time events instead of polling
- **Traits/synergies:** Extensible rules engine with trait system
- **Matchmaking:** ELO-based queuing
- **Spectator mode:** Watch live games
- **Admin dashboard:** Analytics, match inspection, user management
- **Bot API:** AI opponent integration

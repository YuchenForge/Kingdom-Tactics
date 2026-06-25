# Database Schema

Complete PostgreSQL schema for Kingdom Tactics. All migrations are managed by Flyway.

---

## ER Diagram

```
users (1) ──────────── (N) games [player_1_id, player_2_id]
   │                        │
   │                        ├─ (1) ──────── (N) game_players
   │                        │                   │
   │                        │                   ├─ (1) ──── (N) round_plans
   │                        │                   │
   │                        └─ (1) ──────── (N) rounds
   │                                             │
   │                                             ├─ (1) ──── (N) commands
   │                                             │
   │                                             ├─ (1) ──── (N) game_events
   │                                             │
   │                                             ├─ (1) ──── (N) shop_offers
   │                                             │
   │                                             └─ (1) ──── (N) game_state_snapshots
   │
   └──────────────────── (1) ──────── (N) ratings
```

---

## Core tables

### users

Registered players.

```sql
CREATE TABLE users (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  username        VARCHAR(50) NOT NULL UNIQUE,
  email           VARCHAR(100) NOT NULL UNIQUE,
  password_hash   BYTEA NOT NULL,
  rating          INTEGER NOT NULL DEFAULT 1200,
  created_at      TIMESTAMP NOT NULL DEFAULT now(),
  updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);
```

**Fields:**
- `id`: Unique user identifier
- `username`: Display name, must be unique
- `email`: Email address for login
- `password_hash`: Bcrypt hash (never plaintext)
- `rating`: ELO rating (MVP: fixed at 1200, later: dynamic)
- `created_at`: Registration timestamp
- `updated_at`: Last update timestamp (password change, profile update)

---

### games

Match instances. Two players per game.

```sql
CREATE TABLE games (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  player_1_id     UUID NOT NULL REFERENCES users(id),
  player_2_id     UUID NOT NULL REFERENCES users(id),
  state           VARCHAR(50) NOT NULL,
    -- WAITING_FOR_PLAYERS, PREPARATION, LOCKED, RESOLVING, ROUND_RESULT, FINISHED
  current_round   INTEGER NOT NULL DEFAULT 0,
  winner_id       UUID REFERENCES users(id),
    -- NULL until game finishes
  started_at      TIMESTAMP NOT NULL DEFAULT now(),
  finished_at     TIMESTAMP,
  created_at      TIMESTAMP NOT NULL DEFAULT now(),
  
  UNIQUE(player_1_id, player_2_id, started_at),
  CHECK(player_1_id != player_2_id)
);

CREATE INDEX idx_games_state ON games(state);
CREATE INDEX idx_games_winner_id ON games(winner_id);
CREATE INDEX idx_games_player_1_id ON games(player_1_id);
CREATE INDEX idx_games_player_2_id ON games(player_2_id);
```

**Fields:**
- `id`: Unique game identifier
- `player_1_id`, `player_2_id`: Foreign keys to users (seat 0 and seat 1)
- `state`: Current game phase
- `current_round`: Round number (1–8)
- `winner_id`: NULL until winner is determined
- `started_at`: Game creation time (not start of first round)
- `finished_at`: When game ended
- `created_at`: Timestamp

**Constraints:**
- Players must be different (player_1_id ≠ player_2_id)
- Unique on (player_1_id, player_2_id, started_at) to prevent duplicate ongoing games
- Winner is set when game finishes

---

### game_players

Join table for explicit player membership (supports future expansion to 4+ players).

```sql
CREATE TABLE game_players (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  game_id         UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  player_id       UUID NOT NULL REFERENCES users(id),
  seat            INTEGER NOT NULL,
    -- 0 or 1 for MVP (can be 0-3 later)
  is_ready        BOOLEAN NOT NULL DEFAULT FALSE,
  joined_at       TIMESTAMP NOT NULL DEFAULT now(),
  
  UNIQUE(game_id, player_id),
  UNIQUE(game_id, seat)
);

CREATE INDEX idx_game_players_game_id ON game_players(game_id);
CREATE INDEX idx_game_players_player_id ON game_players(player_id);
```

**Fields:**
- `id`: Unique player-game membership record
- `game_id`: Foreign key to games
- `player_id`: Foreign key to users
- `seat`: Position in game (0=player 1, 1=player 2)
- `is_ready`: Player has confirmed they're ready to start
- `joined_at`: When player joined

**Constraints:**
- Each game-player pair is unique
- Each game-seat pair is unique (exactly one player per seat)

---

### rounds

Individual game rounds.

```sql
CREATE TABLE rounds (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  game_id         UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  round_number    INTEGER NOT NULL,
    -- 1–8
  state           VARCHAR(50) NOT NULL,
    -- PREPARATION, LOCKED, RESOLVING, ROUND_RESULT
  rules_version   VARCHAR(20) NOT NULL DEFAULT '1.0',
    -- Enables rules migrations
  combat_seed     BIGINT,
    -- NULL until resolution begins
  planning_deadline TIMESTAMP NOT NULL,
  started_at      TIMESTAMP NOT NULL DEFAULT now(),
  finished_at     TIMESTAMP,
  
  UNIQUE(game_id, round_number)
);

CREATE INDEX idx_rounds_game_id ON rounds(game_id);
CREATE INDEX idx_rounds_state ON rounds(state);
CREATE INDEX idx_rounds_started_at ON rounds(started_at);
```

**Fields:**
- `id`: Unique round identifier
- `game_id`: Foreign key to games
- `round_number`: 1–8
- `state`: Current phase
- `rules_version`: Immutable record of which rules were in effect
- `combat_seed`: RNG seed for deterministic combat
- `planning_deadline`: When planning phase ends (45 seconds after start)
- `started_at`: When round began
- `finished_at`: When round finished resolving
- `created_at`: Implicit (use started_at)

**Constraints:**
- Only one round per (game_id, round_number)

---

### round_plans

Each player's plan for a round.

```sql
CREATE TABLE round_plans (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  round_id            UUID NOT NULL REFERENCES rounds(id) ON DELETE CASCADE,
  player_id           UUID NOT NULL REFERENCES users(id),
  is_locked           BOOLEAN NOT NULL DEFAULT FALSE,
  board_state         JSONB NOT NULL,
    -- { 0: unit_id, 1: null, 2: unit_id, ... }  (0-15 cells)
  bench_units         JSONB NOT NULL DEFAULT '[]',
    -- [ unit_id, unit_id, ... ]
  gold                INTEGER NOT NULL,
  locked_at           TIMESTAMP,
  created_at          TIMESTAMP NOT NULL DEFAULT now(),
  updated_at          TIMESTAMP NOT NULL DEFAULT now(),
  
  UNIQUE(round_id, player_id)
);

CREATE INDEX idx_round_plans_round_id ON round_plans(round_id);
CREATE INDEX idx_round_plans_player_id ON round_plans(player_id);
```

**Fields:**
- `id`: Unique plan identifier
- `round_id`: Foreign key to rounds
- `player_id`: Foreign key to users
- `is_locked`: Immutable once true
- `board_state`: Current board layout (JSONB, updated as commands arrive)
- `bench_units`: List of unit IDs on bench
- `gold`: Current gold balance
- `locked_at`: When player locked the board
- `created_at`, `updated_at`: Timestamps

**Constraints:**
- Exactly one plan per (round_id, player_id)

---

### commands

Immutable log of all planning commands.

```sql
CREATE TABLE commands (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  round_plan_id     UUID NOT NULL REFERENCES round_plans(id) ON DELETE CASCADE,
  sequence_number   INTEGER NOT NULL,
    -- 0-indexed, ordering of commands within a plan
  command_type      VARCHAR(50) NOT NULL,
    -- BUY_UNIT, SELL_UNIT, PLACE_UNIT, MOVE_UNIT, REFRESH_SHOP, LOCK_BOARD
  parameters        JSONB NOT NULL,
    -- { "shopSlot": 2 } or { "unitId": "...", "x": 0, "y": 1 }
  idempotency_key   UUID NOT NULL,
  executed_at       TIMESTAMP NOT NULL DEFAULT now(),
  
  UNIQUE(round_plan_id, sequence_number),
  UNIQUE(round_plan_id, idempotency_key)
    -- Prevent duplicate commands via idempotency key
);

CREATE INDEX idx_commands_round_plan_id ON commands(round_plan_id);
```

**Fields:**
- `id`: Unique command identifier
- `round_plan_id`: Foreign key to round_plans
- `sequence_number`: Order within the plan (0-indexed)
- `command_type`: Type of command
- `parameters`: Command-specific JSON data
- `idempotency_key`: UUID from request header (prevents dupes)
- `executed_at`: When command was processed

**Constraints:**
- Unique on (round_plan_id, sequence_number) — exactly one command per position
- Unique on (round_plan_id, idempotency_key) — only one result per idempotency key

---

### shop_offers

Unit offerings in the shop each round.

```sql
CREATE TABLE shop_offers (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  round_id        UUID NOT NULL REFERENCES rounds(id) ON DELETE CASCADE,
  player_id       UUID NOT NULL REFERENCES users(id),
  slot            INTEGER NOT NULL,
    -- 0–4 (5 shop slots)
  unit_type       VARCHAR(50),
    -- Squire, Shieldbearer, Ranger, Knight, Mage, Healer (NULL if empty)
  created_at      TIMESTAMP NOT NULL DEFAULT now(),
  
  UNIQUE(round_id, player_id, slot)
);

CREATE INDEX idx_shop_offers_round_id ON shop_offers(round_id);
```

**Fields:**
- `id`: Unique shop offer
- `round_id`: Foreign key to rounds
- `player_id`: Foreign key to users
- `slot`: 0–4
- `unit_type`: Unit definition (NULL if slot is empty after refresh)
- `created_at`: When offer was generated

**Constraints:**
- Unique on (round_id, player_id, slot)

---

### game_events

Immutable log of all combat events.

```sql
CREATE TABLE game_events (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  game_id         UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  round_number    INTEGER NOT NULL,
  sequence_num    INTEGER NOT NULL,
    -- 0-indexed event order within round
  event_type      VARCHAR(50) NOT NULL,
    -- UNIT_PLACED, UNIT_MOVED, ATTACK, HEALED, UNIT_DIED, COMBAT_ENDED, etc.
  data            JSONB NOT NULL,
    -- { "unitId": "...", "x": 0, "y": 1, "damage": 5, ... }
  tick            INTEGER NOT NULL,
    -- Logical combat tick (0–160)
  created_at      TIMESTAMP NOT NULL DEFAULT now(),
  
  UNIQUE(game_id, round_number, sequence_num)
);

CREATE INDEX idx_game_events_game_id ON game_events(game_id);
CREATE INDEX idx_game_events_round ON game_events(game_id, round_number);
CREATE INDEX idx_game_events_event_type ON game_events(event_type);
```

**Fields:**
- `id`: Unique event identifier
- `game_id`: Foreign key to games
- `round_number`: Which round (1–8)
- `sequence_num`: Order within round
- `event_type`: Event type
- `data`: Event-specific JSON (varies by type)
- `tick`: Combat tick when event occurred
- `created_at`: When event was recorded

**Constraints:**
- Unique on (game_id, round_number, sequence_num) — no duplicate events

**Event type examples:**
- `UNIT_PLACED`: `{ "unitId": "...", "x": 0, "y": 0 }`
- `UNIT_MOVED`: `{ "unitId": "...", "x": 1, "y": 0 }`
- `ATTACK`: `{ "attackerId": "...", "targetId": "...", "damage": 5 }`
- `HEALED`: `{ "healerId": "...", "targetId": "...", "amount": 5 }`
- `UNIT_DIED`: `{ "unitId": "..." }`
- `COMBAT_ENDED`: `{ "reason": "TIME_LIMIT" | "NO_SURVIVORS" }`

---

### game_state_snapshots

Immutable snapshots of game state before and after each round.

```sql
CREATE TABLE game_state_snapshots (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  game_id         UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  round_number    INTEGER NOT NULL,
  is_round_start  BOOLEAN NOT NULL,
    -- true=start, false=end
  player_id       UUID NOT NULL REFERENCES users(id),
  keep_hp         INTEGER NOT NULL,
  gold            INTEGER NOT NULL,
  board           JSONB NOT NULL,
    -- [ unit_id, null, unit_id, ... ]  (16 cells)
  bench           JSONB NOT NULL,
    -- [ unit_id, ... ]
  shop            JSONB,
    -- [ { "type": "Squire", "cost": 1 }, ... ]  (only at round start)
  created_at      TIMESTAMP NOT NULL DEFAULT now(),
  
  UNIQUE(game_id, round_number, is_round_start, player_id)
);

CREATE INDEX idx_snapshots_game_id ON game_state_snapshots(game_id);
CREATE INDEX idx_snapshots_round ON game_state_snapshots(game_id, round_number);
```

**Fields:**
- `id`: Unique snapshot
- `game_id`: Foreign key to games
- `round_number`: Which round
- `is_round_start`: true=at start, false=at end
- `player_id`: Which player (seat 0 or 1)
- `keep_hp`: Keep HP at this point
- `gold`: Gold balance
- `board`: Board layout (array of 16 cells)
- `bench`: Benched units
- `shop`: Shop offerings (only at round start)
- `created_at`: When snapshot was recorded

**Constraints:**
- Unique on (game_id, round_number, is_round_start, player_id)
- Exactly one snapshot per player per round phase

**Usage:**
- Verify combat outcome: `replay_events(round_start, events) == round_end`
- Restore game state at arbitrary round

---

### ratings

Player rating history (optional MVP, useful for Phase 8+).

```sql
CREATE TABLE ratings (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users(id),
  game_id         UUID NOT NULL REFERENCES games(id),
  old_rating      INTEGER NOT NULL,
  new_rating      INTEGER NOT NULL,
  delta           INTEGER NOT NULL,
    -- new_rating - old_rating (positive for win, negative for loss)
  recorded_at     TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_ratings_user_id ON ratings(user_id);
CREATE INDEX idx_ratings_game_id ON ratings(game_id);
```

**Fields:**
- `id`: Unique rating change record
- `user_id`: Foreign key to users
- `game_id`: Foreign key to games
- `old_rating`, `new_rating`: Before and after
- `delta`: Change (signed)
- `recorded_at`: When change was recorded

**Usage:**
- Audit trail of rating changes
- Revert erroneous ratings
- Analyze rating distribution

---

## Indices strategy

**High-priority indices** (created by default):
- `games(state)` — Find games by phase (PREPARATION, etc.)
- `games(player_1_id, player_2_id)` — Find game between two players
- `rounds(game_id, round_number)` — UNIQUE constraint
- `game_events(game_id, round_number, sequence_num)` — UNIQUE constraint
- `commands(round_plan_id, sequence_number)` — UNIQUE constraint
- `round_plans(round_id, player_id)` — UNIQUE constraint

**Query-specific indices:**
- `games(created_at DESC)` — List recent games
- `game_players(player_id)` — Find all games a user is in
- `game_events(event_type)` — Find all combat events of a type
- `round_plans(player_id)` — Find all plans a user submitted

---

## JSONB schema examples

### board_state

```json
{
  "0": "unit_001",
  "1": "unit_002",
  "2": null,
  "3": null,
  "4": null,
  ...
  "15": null
}
```

Keys are cell indices (0–15). Values are unit IDs or null.

### bench_units

```json
[
  "unit_003",
  "unit_004"
]
```

Array of unit IDs not on board.

### shop

```json
[
  {
    "slot": 0,
    "type": "Squire",
    "cost": 1,
    "hp": 8,
    "atk": 2,
    "rng": 1
  },
  {
    "slot": 1,
    "type": "Ranger",
    "cost": 2,
    "hp": 7,
    "atk": 4,
    "rng": 3
  },
  ...
]
```

Array of 5 shop slots, each with unit definition or null.

### command parameters

```json
{
  "shopSlot": 1
}
```

or

```json
{
  "unitId": "unit_001",
  "x": 0,
  "y": 1
}
```

Command-specific parameters.

### game event data

```json
{
  "unitId": "unit_001",
  "x": 1,
  "y": 0
}
```

or

```json
{
  "attackerId": "unit_001",
  "targetId": "unit_002",
  "damage": 5
}
```

Event-specific fields.

---

## Migrations

Migrations are managed by **Flyway**. All migrations are in `backend/src/main/resources/db/migration/` with naming convention `VN__description.sql`.

Example migration (`V1__initial_schema.sql`):
```sql
CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  ...
);

CREATE TABLE games (
  ...
);

-- ... (all tables)

CREATE INDEX idx_games_state ON games(state);
-- ... (all indices)
```

Flyway automatically:
- Creates the schema_version table
- Tracks applied migrations
- Prevents re-running migrations
- Detects out-of-order migrations (error)

---

## Backup and restore

### Backup

```bash
pg_dump -U kingdom_user kingdom_tactics > backup.sql
```

### Restore

```bash
psql -U kingdom_user kingdom_tactics < backup.sql
```

### Point-in-time recovery

```bash
# Set recovery target time
ALTER SYSTEM SET recovery_target_timeline = 'latest';
ALTER SYSTEM SET recovery_target_time = '2024-01-15 14:00:00';
pg_ctl restart
```

---

## Performance tuning (future)

- **Partitioning:** `game_events` can be partitioned by game_id for very large datasets
- **Archiving:** Old games (>1 year) can be archived to cold storage
- **Denormalization:** Cache frequently-accessed player stats in `users` table
- **Caching:** Redis for user sessions, game state cache (after Phase 2)

---

## Testing

### Schema validation test

```java
@Test
@DataJpaTest
void database_schema_is_valid() {
  // JPA validates schema on startup
  assertThat(userRepository.findAll()).isEmpty();
  assertThat(gameRepository.findAll()).isEmpty();
}
```

### Test data creation

```sql
-- test-data.sql
INSERT INTO users VALUES (...);
INSERT INTO games VALUES (...);
```

Applied via:
```java
@Test
@Sql("/test-data.sql")
void two_users_can_play() {
  // Uses test data from file
}
```

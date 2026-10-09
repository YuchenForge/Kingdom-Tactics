# API Contract

REST API contract for the implemented Kingdom Tactics endpoints. All endpoints require HTTPS in production.

Base URL: `http://localhost:8080/api`

---

## Authentication endpoints

### Register a new user

```
POST /auth/register
```

**Request:**
```json
{
  "username": "player1",
  "email": "player1@example.com",
  "password": "password123"
}
```

**Response:** `201 Created`
```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "player1",
  "email": "player1@example.com",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "createdAt": "2024-01-15T14:23:45Z"
}
```

**Validation:**
- `username`: 3–50 characters
- `email`: valid email, max 100 characters
- `password`: min 8 characters, max **72 UTF-8 bytes** (BCrypt input limit; longer passwords are rejected, not truncated)

**Errors:**
- `400 Bad Request` — Missing/invalid fields, or password exceeds 72 UTF-8 bytes
- `409 Conflict` — Username or email already exists

---

### Login

```
POST /auth/login
```

**Request:**
```json
{
  "email": "player1@example.com",
  "password": "password123"
}
```

**Response:** `200 OK`
```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "player1",
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 86400
}
```

**Validation:**
- `password`: max **72 UTF-8 bytes** (same ceiling as register)

**Errors:**
- `400 Bad Request` — Missing email/password, or password exceeds 72 UTF-8 bytes
- `401 Unauthorized` — Invalid email or password

---

### Get current user

```
GET /me
```

**Headers:**
```
Authorization: Bearer <token>
```

**Response:** `200 OK`
```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "player1",
  "email": "player1@example.com",
  "rating": 1200,
  "createdAt": "2024-01-15T14:23:45Z"
}
```

**Errors:**
- `401 Unauthorized` — Missing or invalid token

---

### Logout (client-side)

There is no server logout endpoint. Clear the JWT on the client (discard token / local storage).

## Game management endpoints

### Create a new game

```
POST /games
```

**Headers:**
```
Authorization: Bearer <token>
Content-Type: application/json
```

**Request:**
```json
{}
```

**Response:** `201 Created`
```json
{
  "gameId": "550e8400-e29b-41d4-a716-446655440000",
  "state": "WAITING_FOR_PLAYERS",
  "currentRound": 0,
  "players": [
    {
      "playerId": "550e8400-e29b-41d4-a716-446655440000",
      "username": "player1",
      "keepHp": 20,
      "gold": 10,
      "seat": 0
    }
  ],
  "createdAt": "2024-01-15T14:23:45Z",
  "planningDeadline": null,
  "startedAt": "2024-01-15T14:23:45Z"
}
```

**Errors:**
- `401 Unauthorized` — Not authenticated

---

### Join an existing game

```
POST /games/{gameId}/join
```

**Headers:**
```
Authorization: Bearer <token>
```

**Path Parameters:**
- `gameId` (UUID) — Game to join

**Response:** `200 OK`
```json
{
  "gameId": "550e8400-e29b-41d4-a716-446655440000",
  "state": "PREPARATION",
  "currentRound": 1,
  "players": [
    {
      "playerId": "550e8400-e29b-41d4-a716-446655440001",
      "username": "player1",
      "keepHp": 20,
      "gold": 10,
      "seat": 0
    },
    {
      "playerId": "550e8400-e29b-41d4-a716-446655440002",
      "username": "player2",
      "keepHp": 20,
      "gold": 10,
      "seat": 1
    }
  ],
  "planningDeadline": "2024-01-15T14:24:30Z",
  "createdAt": "2024-01-15T14:23:45Z",
  "startedAt": "2024-01-15T14:23:45Z"
}
```

**Errors:**
- `404 Not Found` — Game doesn't exist
- `409 Conflict` — Game is full or already started
- `400 Bad Request` — User is already in the game

---

### Get game overview

```
GET /games/{gameId}
```

**Headers:**
```
Authorization: Bearer <token>
```

**Response:** `200 OK`
```json
{
  "gameId": "550e8400-e29b-41d4-a716-446655440000",
  "state": "PREPARATION",
  "currentRound": 1,
  "players": [
    {
      "playerId": "550e8400-e29b-41d4-a716-446655440001",
      "username": "player1",
      "keepHp": 20,
      "gold": 10,
      "seat": 0
    },
    {
      "playerId": "550e8400-e29b-41d4-a716-446655440002",
      "username": "player2",
      "keepHp": 18,
      "gold": 10,
      "seat": 1
    }
  ],
  "planningDeadline": "2024-01-15T14:24:30Z",
  "startedAt": "2024-01-15T14:22:45Z",
  "createdAt": "2024-01-15T14:23:45Z"
}
```

---

### Get game state (detailed)

```
GET /games/{gameId}/state
```

**Headers:**
```
Authorization: Bearer <token>
```

**Response:** `200 OK`
```json
{
  "gameId": "550e8400-e29b-41d4-a716-446655440000",
  "state": "PREPARATION",
  "currentRound": 2,
  "latestResolvedRound": 1,
  "serverTime": "2026-10-04T12:00:00Z",
  "combatPresentation": null,
  "yourSeat": 0,
  "yourKeepHp": 20,
  "yourGold": 15,
  "opponentKeepHp": 17,
  "opponentUnitCount": 2,
  "yourBoard": [
    [
      null,
      null,
      null,
      null
    ],
    [
      null,
      null,
      null,
      null
    ],
    [
      "unit_001",
      "unit_002",
      null,
      null
    ],
    [
      null,
      null,
      null,
      null
    ]
  ],
  "yourUnits": {
    "unit_001": {
      "id": "unit_001",
      "unitType": "Squire",
      "level": 2,
      "maxHp": 12,
      "attack": 3,
      "range": 1,
      "specialAbility": "None",
      "healAmount": 0,
      "sellRefund": 2
    },
    "unit_002": {
      "id": "unit_002",
      "unitType": "Ranger",
      "level": 1,
      "maxHp": 7,
      "attack": 4,
      "range": 3,
      "specialAbility": "LowestHpTarget",
      "healAmount": 0,
      "sellRefund": 2
    },
    "unit_003": {
      "id": "unit_003",
      "unitType": "Ranger",
      "level": 1,
      "maxHp": 7,
      "attack": 4,
      "range": 3,
      "specialAbility": "LowestHpTarget",
      "healAmount": 0,
      "sellRefund": 2
    }
  },
  "yourLane": [
    {
      "slot": 0,
      "unitId": "unit_003",
      "unitType": "Ranger",
      "level": 1
    },
    {
      "slot": 1,
      "unitId": null,
      "unitType": null,
      "level": null
    },
    {
      "slot": 2,
      "unitId": null,
      "unitType": null,
      "level": null
    },
    {
      "slot": 3,
      "unitId": null,
      "unitType": null,
      "level": null
    },
    {
      "slot": 4,
      "unitId": null,
      "unitType": null,
      "level": null
    }
  ],
  "shop": [
    {
      "slot": 0,
      "unitType": "Squire",
      "cost": 1,
      "maxHp": 8,
      "attack": 2,
      "range": 1,
      "specialAbility": "None",
      "healAmount": 0
    },
    {
      "slot": 1,
      "unitType": "Knight",
      "cost": 3,
      "maxHp": 18,
      "attack": 5,
      "range": 1,
      "specialAbility": "NearestTarget",
      "healAmount": 0
    },
    {
      "slot": 2,
      "unitType": "Ranger",
      "cost": 2,
      "maxHp": 7,
      "attack": 4,
      "range": 3,
      "specialAbility": "LowestHpTarget",
      "healAmount": 0
    }
  ],
  "planningDeadline": "2026-10-04T12:00:45Z",
  "isLocked": false,
  "opponentIsLocked": true,
  "combatUnits": []
}
```

Notes:
- `yourBoard` is a 4×4 grid of unit IDs (or null). Persistence still has `{id,type,level}` in `round_plans.board_state`.
- `yourUnits` maps each board + lane unit id → `unitType`, `level`, and effective display stats from server `UnitDefinition` (level-scaled). Required for reload-safe UI: placed units leave the lane, and auto-merge changes level without client history.
- Each unit view includes integer `sellRefund`: gold returned by selling that unit, computed server-side by `PlanningHelpers.sellRefund(unitType, level)` (`base cost × level`). This is reconstructed for board and lane units on every read, including after auto-merge; clients must not maintain a cost table.
- Command snapshots, including idempotent retries, use the same shape and `sellRefund` under `units` (not `yourUnits`).
- `yourLane` / `shop` use `unitType`. Shop display stats are Level-1 recruit stats from `UnitDefinition` (`maxHp`, `attack`, `range`, `specialAbility`, `healAmount`). Empty/sold slots: `unitType` null, numeric fields 0, `specialAbility` null.
- `latestResolvedRound` = max round with TX 2 committed (`outcome IS NOT NULL`); **null** until the first resolve. Lets reconnecting clients retrieve results after the presentation window.
- `opponentUnitCount` = opponent **board + lane** units (not “placed-only”).
- `state` may be `PREPARATION` / `LOCKED` / `RESOLVING` / `ROUND_RESULT` / `FINISHED` (not only planning). Keep HP on `/state` is live `game_players` (post-damage after TX 2).
- **Read coherence:** `/state` and `/games/{id}` assemble under Postgres `REPEATABLE READ` so a poll never mixes an older `state`/`currentRound` with newer Keep HP, plans, or shops if TX2/TX3 commits mid-read. Events / round result / match result use the same isolation.

**Visibility:** During preparation, opponent board, shop, and pending plan stay hidden. Once preparation closes, `combatUnits` reveals both deployed formations in `LOCKED`, `RESOLVING`, and `ROUND_RESULT`. Events and round results reveal combat data after resolution. The opponent’s private shop and unused lane remain hidden.

**Errors:**
- `404 Not Found` — Game doesn't exist
- `403 Forbidden` — User is not in game
- `409 GAME_NOT_READY` — Game is waiting for a second player

---

## Planning commands (PREPARATION phase)

All planning commands require:

**Headers:**
```
Authorization: Bearer <token>
Idempotency-Key: <UUID>
Content-Type: application/json
```

Idempotency-Key ensures the same request sent twice has the same effect as sending it once **while still in a supported planning/locked state for that round**.

**Late retries (implemented behavior):** Phase/current-round guards run before the idempotency lookup. Retrying after resolution or advancement returns **`409 WRONG_GAME_STATE`** even if the original command succeeded. Clients must refetch current state and must not silently resubmit into the next round.

### Buy a unit from shop

```
POST /games/{gameId}/rounds/{roundNumber}/buy
```

**Request:**
```json
{
  "shopSlot": 1
}
```

**Response:** `200 OK`
```json
{
  "success": true,
  "gold": 7,
  "lane": [
    {
      "slot": 0,
      "unitId": "unit_003",
      "unitType": "Knight",
      "level": 1
    },
    {
      "slot": 1,
      "unitId": null,
      "unitType": null,
      "level": null
    },
    {
      "slot": 2,
      "unitId": null,
      "unitType": null,
      "level": null
    },
    {
      "slot": 3,
      "unitId": null,
      "unitType": null,
      "level": null
    },
    {
      "slot": 4,
      "unitId": null,
      "unitType": null,
      "level": null
    }
  ],
  "board": [
    [
      null,
      null,
      null,
      null
    ],
    [
      null,
      null,
      null,
      null
    ],
    [
      null,
      null,
      null,
      null
    ],
    [
      null,
      null,
      null,
      null
    ]
  ],
  "units": {
    "unit_003": {
      "id": "unit_003",
      "unitType": "Knight",
      "level": 1,
      "maxHp": 18,
      "attack": 5,
      "range": 1,
      "specialAbility": "NearestTarget",
      "healAmount": 0,
      "sellRefund": 3
    }
  },
  "shop": [
    {
      "slot": 0,
      "unitType": "Squire",
      "cost": 1,
      "maxHp": 8,
      "attack": 2,
      "range": 1,
      "specialAbility": "None",
      "healAmount": 0
    },
    {
      "slot": 1,
      "unitType": null,
      "cost": 0,
      "maxHp": 0,
      "attack": 0,
      "range": 0,
      "specialAbility": null,
      "healAmount": 0
    },
    {
      "slot": 2,
      "unitType": "Ranger",
      "cost": 2,
      "maxHp": 7,
      "attack": 4,
      "range": 3,
      "specialAbility": "LowestHpTarget",
      "healAmount": 0
    }
  ],
  "isLocked": false
}
```

**Errors:**
- `400 Bad Request` — Invalid shop slot or insufficient gold
- `409 Conflict` — Shop slot is empty
- `423 Locked` — Round is locked, cannot modify

---

### Refresh the shop

```
POST /games/{gameId}/rounds/{roundNumber}/refresh
```

**Request:**
```json
{}
```

**Response:** `200 OK` with the complete command snapshot shown in the buy example: `success`, `gold`, five `lane` slots, 4×4 `board`, `units`, three `shop` slots, and `isLocked`.

**Errors:**
- `400 Bad Request` — Insufficient gold (needs 1)
- `423 Locked` — Round is locked

---

### Sell a unit

```
POST /games/{gameId}/rounds/{roundNumber}/sell
```

**Request:**
```json
{
  "unitId": "unit_001"
}
```

**Response:** `200 OK` with the complete command snapshot shown in the buy example: `success`, `gold`, five `lane` slots, 4×4 `board`, `units`, three `shop` slots, and `isLocked`.

**Errors:**
- `404 Not Found` — Unit not found or not owned
- `423 Locked` — Round is locked

---

### Relocate a unit (lane ↔ board)

```
POST /games/{gameId}/rounds/{roundNumber}/relocate
```

Moves a unit to a board cell or holding-lane slot. Covers lane→board, board→board, lane→lane, and board→lane.

**Request (to board):**
```json
{
  "unitId": "unit_001",
  "to": {
    "type": "BOARD",
    "x": 0,
    "y": 0
  }
}
```

**Request (to lane):**
```json
{
  "unitId": "unit_001",
  "to": {
    "type": "LANE",
    "slot": 2
  }
}
```

**Response:** `200 OK` with the complete command snapshot shown in the buy example: `success`, `gold`, five `lane` slots, 4×4 `board`, `units`, three `shop` slots, and `isLocked`.

All planning command responses use the same snapshot fields as buy: `units` (id → display stats) and shop slots with Level-1 `maxHp`/`attack`/`range`/`specialAbility`/`healAmount` (see buy example).

**Errors:**
- `400 Bad Request` — Out of bounds, board cap exceeded (when entering board from lane), invalid lane slot
- `404 Not Found` — Unit not found or not owned
- `409 Conflict` — Destination cell/slot occupied by another unit
- `423 Locked` — Round is locked

---

### Lock board (submit final plan)

```
POST /games/{gameId}/rounds/{roundNumber}/lock
```

**Request:**
```json
{}
```

**Response:** `200 OK` with the complete command snapshot shown in the buy example: `success`, `gold`, five `lane` slots, 4×4 `board`, `units`, three `shop` slots, and `isLocked`.

The lock response also includes `opponentIsLocked` and `message`. When both players are locked it adds `nextState: "LOCKED"`; otherwise `nextState` is omitted. These fields also describe the current state on a supported idempotent lock retry.

**Errors:**
- `423 Locked` — Board already locked

---

## Event streaming (RESOLVING / after resolution)

### Get combat events (polling / catch-up)

Canonical endpoint (one source of truth):

```
GET /games/{gameId}/events?round=1&afterSequence=0&limit=200
```

**Query Parameters:**
- `round` (int, required) — Round number (1–8)
- `afterSequence` (int, optional) — Exclusive cursor; return events with `sequenceNumber` **greater than** this value. Default: `0` (so the first event is `1`)
- `limit` (int, optional) — Page size (default 200, positive values clamped to 500)

**Auth:** Participants only for player-game endpoints.

**Response:** `200 OK` — **excerpt of page 1** after TX 2 committed (`complete: true`; `hasMore` means more pages)
```json
{
  "roundNumber": 1,
  "events": [
    {
      "sequenceNumber": 1,
      "type": "UNIT_PLACED",
      "tick": 0,
      "data": {
        "unitId": "unit_001",
        "unitType": "Knight",
        "x": 0,
        "y": 0,
        "playerId": 0,
        "level": 2,
        "currentHp": 27,
        "maxHp": 27
      }
    },
    {
      "sequenceNumber": 2,
      "type": "ATTACK",
      "tick": 3,
      "data": {
        "attackerId": "unit_001",
        "targetId": "unit_002",
        "damage": 6
      }
    }
  ],
  "nextAfterSequence": 2,
  "hasMore": true,
  "complete": true
}
```

**Semantics:**
- Combat event sequences are **1-based** and restart each round.
- Planning **command** sequences remain **0-based** (separate log; no migration).
- `complete` = TX 2 resolution was durably committed for this round (the event set is final; further pages only catch up). While still `RESOLVING` / no TX 2: empty `events`, `complete: false`.
- `hasMore` = more pages remain for the current query (`afterSequence` / `limit`).
- Client deduplication key: `gameId + round + sequenceNumber`.

**Event types (combat tick stream):**
- `UNIT_PLACED` — includes `level`, `currentHp`, `maxHp` (Phase 4 engine enrichment)
- `UNIT_MOVED`, `ATTACK`, `HEALED`, `UNIT_DIED`
- `COMBAT_ENDED` — `data.reason` is a `CombatOutcome`: `PLAYER_VICTORY`, `ENEMY_VICTORY`, `TIME_LIMIT`, or `DRAW` (`PLAYER`/`ENEMY` = seat 0 / seat 1, not the requesting user)

> **Note on tick numbers:** Units start with cooldown 4. Cooldowns decrement at the beginning of each tick, so the first `UNIT_MOVED` / `ATTACK` events typically occur on **tick 3**.

**Game phase transitions (not combat events):** `ROUND_RESULT` is a round/game phase (see `docs/state-machine.md`), not a `CombatEvent` type. Prefer `GET .../rounds/{n}/result` and `latestResolvedRound` on `/state` — reconnecting clients may return after the persisted presentation window.

---

## Round and match results

### Get round result

```
GET /games/{gameId}/rounds/{roundNumber}/result
```

**Response:** `200 OK` — immutable after TX 2; still available after TX 3 advancement
```json
{
  "roundNumber": 1,
  "outcome": "PLAYER_VICTORY",
  "keepDamage": {
    "0": 0,
    "1": 3
  },
  "keepHpAfter": {
    "0": 20,
    "1": 17
  },
  "endSnapshots": {
    "0": {
      "survivors": [
        {
          "id": "unit_001",
          "type": "Knight",
          "level": 1,
          "x": 1,
          "y": 3,
          "currentHp": 13,
          "maxHp": 18
        }
      ],
      "keepHp": 20
    },
    "1": {
      "survivors": [],
      "keepHp": 17
    }
  }
}
```

**Outcome vocabulary** (same as engine / `COMBAT_ENDED.reason`; shared type
`com.kingdom.engine.domain.CombatOutcome`):  
`PLAYER_VICTORY` | `ENEMY_VICTORY` | `TIME_LIMIT` | `DRAW`

Seat semantics (not relative to the HTTP caller):
- `PLAYER_*` = **seat 0**
- `ENEMY_*` = **seat 1**

A requesting user in seat 1 who wins still sees `ENEMY_VICTORY` on the round result.

**Durable storage map (Phase 4):**

| Response field | Source (do not recompute from live `game_players` for history) |
|----------------|----------------------------------------------------------------|
| `outcome` | `rounds.outcome` (set in TX 2) |
| `keepDamage` | `rounds.keep_damage` JSONB `{"0":n,"1":n}` (set in TX 2) |
| `keepHpAfter` | `game_state_snapshots.keep_hp` per player (combat-end) |
| `endSnapshots[].survivors` | `game_state_snapshots.board` (combat-end survivors) |
| `endSnapshots[].keepHp` | same snapshot row’s `keep_hp` |

Locked planning formations stay in `round_plans` and are **not** replaced by combat survivors. Next-round plans are a **new copy** of the locked formation at full HP (TX 3).

`GET /state` also includes `latestResolvedRound` (max round with TX 2 committed) so reconnecting clients need not observe `ROUND_RESULT` live.

---

## Match results and history

### Get match result

```
GET /games/{gameId}/result
```

**Response:** `200 OK` — available when `games.state = FINISHED`
```json
{
  "gameId": "game_...",
  "state": "FINISHED",
  "winnerId": "550e8400-e29b-41d4-a716-446655440001",
  "winnerUsername": "player1",
  "loserUsername": "player2",
  "finalKeepHp": [
    12,
    0
  ],
  "finalRound": 3,
  "durationSeconds": 450,
  "finishedAt": "2024-01-15T15:23:45Z"
}
```

**Draw / null:** Match draw → `winnerId`, `winnerUsername`, and `loserUsername` are **`null`**. `games.winner_id` stays NULL when FINISHED means draw (both Keeps ≤ 0, or round-8 tie with equal Keep then equal gold).

**Storage:** `games.winner_id` / `games.finished_at` / `games.current_round`; `finalKeepHp` from `game_players.keep_hp` (live at end) or last-round end snapshots.

---

### Play again

Reuse create/join for a new game. Dedicated rematch and match-history UI are not implemented.

---

### Features outside the current contract

There is no replay-export, match-history, rematch, or logout endpoint. Playback uses event pages and round results. Start another match through create/join. The browser does not offer a separate historical replay player.

---

## Error responses

All errors follow this format:

```json
{
  "error": "INSUFFICIENT_GOLD",
  "message": "INSUFFICIENT_GOLD",
  "status": 400,
  "timestamp": "2024-01-15T14:23:45Z",
  "requestId": "req_550e8400-e29b-41d4-a716-446655440000"
}
```

**Error codes returned by the current handlers and planning engine:**
| HTTP | Error codes |
|---|---|
| 400 | `VALIDATION_ERROR`, `ALREADY_IN_GAME`, `INSUFFICIENT_GOLD`, `LANE_FULL`, `INVALID_UNIT_TYPE`, `OUT_OF_BOUNDS`, `BOARD_CAP_EXCEEDED`, `INVALID_REFRESH_OFFERS` |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN`, `NOT_GAME_PARTICIPANT` |
| 404 | `GAME_NOT_FOUND`, `ROUND_NOT_FOUND`, `UNIT_NOT_FOUND` |
| 409 | `CONFLICT`, `GAME_FULL`, `GAME_NOT_READY`, `WRONG_GAME_STATE`, `EMPTY_SHOP_SLOT`, `CELL_OCCUPIED`, `SLOT_OCCUPIED` |
| 423 | `LOCKED`, `ALREADY_LOCKED`, `DEADLINE_PASSED` |
| 500 | `INTERNAL_ERROR` |

The API does not return `429`. There is no rate limiter.

---

## Rate limiting

Not implemented. Add it only if abuse appears. The client polls every 250 ms during `LOCKED`, `RESOLVING`, and `ROUND_RESULT`, and every 1 second in preparation.

---

## Status codes summary

| Code | Meaning |
|------|---------|
| `200` | Success (GET, POST without creation) |
| `201` | Created (POST with creation) |
| `400` | Bad request (validation error) |
| `401` | Unauthorized (not authenticated) |
| `403` | Forbidden (authenticated but not authorized) |
| `404` | Not found |
| `409` | Conflict (duplicate, incompatible state) |
| `423` | Locked (resource locked, cannot modify) |
| `500` | Internal server error |

---

## Headers

**Required for all authenticated requests:**
```
Authorization: Bearer <token>
```

**For JSON requests:** `Content-Type: application/json`. All five planning commands require `Idempotency-Key: <UUID>`; create/join and authentication do not. `X-Request-ID` is optional for tracing; the server generates one when absent.

---

## Testing the API

### Using cURL

```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "test_user",
    "email": "test@example.com",
    "password": "password123"
  }'

# Login
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}' \
  | jq -r '.token')

# Create game
curl -X POST http://localhost:8080/api/games \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{}'
```

### OpenAPI

There is no generated OpenAPI document or Swagger UI. This file is the contract.

### Combat formation on state polls

`GET /games/{id}/state` includes `combatUnits`: an array of `{ id, type, level, seat, x, y }` for both deployed formations in `LOCKED`, `RESOLVING`, and `ROUND_RESULT`. Coordinates are engine-global (`x: 0..3`, `y: 0..7`), computed by the server from the locked plans. Lane units and shop offers are excluded. In preparation and all other states the array is empty, so the opponent's planning formation remains private. These are starting positions, not final survivors or a client combat simulation. The UI returns to the planning board when the server enters the next preparation.

### Combat presentation timing

`GET /games/{gameId}/state` includes `serverTime` (UTC ISO-8601 timestamp captured near the end of response assembly) in every successful response. The frontend anchors this response-assembly timestamp to receipt using performance.now, without adding half the request duration (which includes server processing). It conservatively lags by response transit time. An observed round retains its original clock anchor and persisted schedule while polling continues; reloads join the current server timeline.

`combatPresentation` is the current round's persisted schedule while game state is `ROUND_RESULT`:

```json
{
  "roundNumber": 1,
  "startsAt": "2026-10-04T12:00:03Z",
  "combatEndsAt": "2026-10-04T12:00:08Z",
  "endsAt": "2026-10-04T12:00:12Z",
  "tickDurationMs": 250
}
```

Both seats receive the same schedule, from the same consistent database snapshot as the game state and round. `startsAt` is tick 0, after a 3-second lead-in. `combatEndsAt = startsAt + finalTick × tickDurationMs`; empty ticks count. `endsAt` includes a further 4 seconds: 1 second for final effects followed by 3 seconds of result feedback. The example assumes resolution at 12:00:00Z and finalTick 20. Clients use the persisted timestamps rather than calculating a new deadline. These are presentation durations, not changes to engine action speed. Values are saved once with combat results; polls and reconnects never reset them.

The object is explicitly `null` in PREPARATION, LOCKED, RESOLVING, and FINISHED, and for legacy ROUND_RESULT records without saved timing. A waiting game still returns the existing not-ready error. Authorization remains participant-only. No new endpoint or browser acknowledgment is introduced.

If the deadline has passed but the worker has not advanced yet, ROUND_RESULT still returns the original schedule. Show the completed result and keep polling; never restart playback or locally enter planning. After advancement the object becomes null, and planning gets its full 45 seconds from actual advancement. Late/reconnecting clients skip elapsed effects and join the current timeline; the existing paginated events and result endpoints remain unchanged.

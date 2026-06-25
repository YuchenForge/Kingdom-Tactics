# API Contract

Complete REST API specification for Kingdom Tactics. All endpoints require HTTPS in production.

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

**Errors:**
- `400 Bad Request` — Missing fields or invalid email format
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

**Errors:**
- `400 Bad Request` — Missing email or password
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

### Logout

```
POST /auth/logout
```

**Headers:**
```
Authorization: Bearer <token>
```

**Response:** `204 No Content`

---

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
  "gameId": "game_550e8400-e29b-41d4-a716-446655440000",
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
  "createdAt": "2024-01-15T14:23:45Z"
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
  "gameId": "game_550e8400-e29b-41d4-a716-446655440000",
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
  ]
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
  "gameId": "game_550e8400-e29b-41d4-a716-446655440000",
  "state": "PREPARATION",
  "currentRound": 1,
  "players": [
    {
      "playerId": "550e8400-e29b-41d4-a716-446655440001",
      "username": "player1",
      "keepHp": 20,
      "gold": 10,
      "seat": 0,
      "isReady": false
    },
    {
      "playerId": "550e8400-e29b-41d4-a716-446655440002",
      "username": "player2",
      "keepHp": 18,
      "gold": 10,
      "seat": 1,
      "isReady": true
    }
  ],
  "planningDeadline": "2024-01-15T14:24:30Z",
  "startedAt": "2024-01-15T14:22:45Z"
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
  "gameId": "game_550e8400-e29b-41d4-a716-446655440000",
  "state": "PREPARATION",
  "currentRound": 1,
  "yourSeat": 0,
  "yourKeepHp": 20,
  "yourGold": 10,
  "opponentKeepHp": 20,
  "opponentUnitCount": 2,
  "yourBoard": [
    [null, null, null, null],
    [null, null, null, null],
    ["unit_001", "unit_002", null, null],
    [null, null, null, null]
  ],
  "yourBench": [
    { "unitId": "unit_003", "type": "Ranger", "cost": 2, "hp": 7 }
  ],
  "shop": [
    { "slot": 0, "type": "Squire", "cost": 1, "hp": 8, "atk": 2, "rng": 1 },
    { "slot": 1, "type": "Knight", "cost": 3, "hp": 18, "atk": 5, "rng": 1 },
    { "slot": 2, "type": "Ranger", "cost": 2, "hp": 7, "atk": 4, "rng": 3 },
    { "slot": 3, null, null, null, null, null },
    { "slot": 4, null, null, null, null, null }
  ],
  "planningDeadline": "2024-01-15T14:24:30Z",
  "isLocked": false,
  "opponentIsLocked": true
}
```

**Errors:**
- `404 Not Found` — Game doesn't exist
- `403 Forbidden` — User is not in game

---

## Planning commands (PREPARATION phase)

All planning commands require:

**Headers:**
```
Authorization: Bearer <token>
Idempotency-Key: <UUID>
Content-Type: application/json
```

Idempotency-Key ensures the same request sent twice has the same effect as sending it once.

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
  "bench": [
    { "unitId": "unit_003", "type": "Knight", "cost": 3 }
  ]
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

**Response:** `200 OK`
```json
{
  "success": true,
  "gold": 9,
  "shop": [
    { "slot": 0, "type": "Squire", "cost": 1 },
    { "slot": 1, "type": "Shieldbearer", "cost": 2 },
    { "slot": 2, "type": "Mage", "cost": 3 },
    { "slot": 3, null },
    { "slot": 4, null }
  ]
}
```

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

**Response:** `200 OK`
```json
{
  "success": true,
  "gold": 11,
  "board": [ ... ],
  "bench": [ ... ]
}
```

**Errors:**
- `404 Not Found` — Unit not found or not owned
- `423 Locked` — Round is locked

---

### Place a unit on board

```
POST /games/{gameId}/rounds/{roundNumber}/place
```

**Request:**
```json
{
  "unitId": "unit_001",
  "x": 0,
  "y": 0
}
```

**Response:** `200 OK`
```json
{
  "success": true,
  "board": [
    ["unit_001", null, null, null],
    [null, null, null, null],
    [null, null, null, null],
    [null, null, null, null]
  ],
  "bench": []
}
```

**Errors:**
- `400 Bad Request` — Position out of bounds, unit already placed, or capacity exceeded
- `409 Conflict` — Position already occupied
- `423 Locked` — Round is locked

---

### Move a unit on board

```
POST /games/{gameId}/rounds/{roundNumber}/move
```

**Request:**
```json
{
  "unitId": "unit_001",
  "x": 1,
  "y": 0
}
```

**Response:** `200 OK` (same format as place)

**Errors:**
- `400 Bad Request` — Position out of bounds or unit not on board
- `409 Conflict` — Position already occupied
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

**Response:** `200 OK`
```json
{
  "success": true,
  "isLocked": true,
  "opponentIsLocked": false,
  "message": "Board locked. Waiting for opponent..."
}
```

**Special response when both players locked:** `200 OK`
```json
{
  "success": true,
  "isLocked": true,
  "opponentIsLocked": true,
  "message": "Both players locked! Combat will begin shortly.",
  "nextState": "LOCKED"
}
```

**Errors:**
- `423 Locked` — Board already locked

---

## Event streaming (RESOLVING / COMBAT phase)

### Get combat events (polling)

```
GET /games/{gameId}/events?afterSequence=0
```

**Query Parameters:**
- `afterSequence` (int, optional) — Return events after this sequence number. Default: 0

**Response:** `200 OK`
```json
{
  "events": [
    {
      "sequenceNumber": 1,
      "type": "UNIT_PLACED",
      "unitId": "unit_001",
      "unitType": "Knight",
      "x": 0,
      "y": 0,
      "tick": 0
    },
    {
      "sequenceNumber": 2,
      "type": "UNIT_PLACED",
      "unitId": "unit_002",
      "unitType": "Squire",
      "x": 3,
      "y": 3,
      "tick": 0
    },
    {
      "sequenceNumber": 3,
      "type": "UNIT_MOVED",
      "unitId": "unit_001",
      "x": 1,
      "y": 0,
      "tick": 1
    },
    {
      "sequenceNumber": 4,
      "type": "ATTACK",
      "attackerId": "unit_001",
      "targetId": "unit_002",
      "damage": 5,
      "tick": 4
    },
    {
      "sequenceNumber": 5,
      "type": "UNIT_DIED",
      "unitId": "unit_002",
      "tick": 4
    }
  ],
  "roundState": "RESOLVING",
  "hasMore": true
}
```

**Event types:**
- `UNIT_PLACED` — Unit placed at start of combat
- `UNIT_MOVED` — Unit moved one tile
- `ATTACK` — Unit attacked and dealt damage
- `HEALED` — Unit was healed
- `UNIT_DIED` — Unit reduced to 0 HP
- `COMBAT_ENDED` — Combat finished (time limit or no survivors)
- `ROUND_RESULT` — Round outcome determined

---

## Replay endpoints

### Get full match replay

```
GET /games/{gameId}/replay
```

**Response:** `200 OK`
```json
{
  "gameId": "game_...",
  "players": [
    { "playerId": "...", "username": "player1", "seat": 0 },
    { "playerId": "...", "username": "player2", "seat": 1 }
  ],
  "winnerId": "...",
  "rounds": [
    {
      "roundNumber": 1,
      "rulesVersion": "1.0",
      "combatSeed": 12345,
      "startSnapshot": {
        "keepHp": [20, 20],
        "gold": [10, 10],
        "board": [ [...], [...] ],
        "bench": [ [...], [...] ]
      },
      "playerCommands": [
        [
          { "type": "BUY_UNIT", "shopSlot": 0 },
          { "type": "PLACE_UNIT", "unitId": "...", "x": 0, "y": 0 }
        ],
        [ ... ]
      ],
      "events": [ ... ],
      "endSnapshot": {
        "keepHp": [20, 18],
        "survivingUnits": [ ... ]
      }
    }
  ]
}
```

---

### Get events for specific round

```
GET /games/{gameId}/rounds/{roundNumber}/events
```

**Response:** `200 OK`
```json
{
  "roundNumber": 1,
  "events": [ ... ]
}
```

---

## Match results and history

### Get match result

```
GET /games/{gameId}/result
```

**Response:** `200 OK`
```json
{
  "gameId": "game_...",
  "state": "FINISHED",
  "winnerId": "550e8400-e29b-41d4-a716-446655440001",
  "winnerUsername": "player1",
  "loserUsername": "player2",
  "finalKeepHp": [12, 0],
  "finalRound": 3,
  "durationSeconds": 450,
  "finishedAt": "2024-01-15T15:23:45Z",
  "rematchGameId": null
}
```

---

### Start a rematch

```
POST /games/{gameId}/rematch
```

**Response:** `201 Created`
```json
{
  "rematchGameId": "game_new-id",
  "state": "WAITING_FOR_PLAYERS"
}
```

---

### Get match history

```
GET /users/{userId}/matches?limit=20&offset=0
```

**Query Parameters:**
- `limit` (int, default 20)
- `offset` (int, default 0)

**Response:** `200 OK`
```json
{
  "total": 42,
  "matches": [
    {
      "gameId": "game_...",
      "opponent": "player2",
      "opponentRating": 1250,
      "result": "WIN",
      "ratingDelta": 25,
      "finalRound": 8,
      "finishedAt": "2024-01-15T15:23:45Z"
    }
  ]
}
```

---

## Error responses

All errors follow this format:

```json
{
  "error": "INVALID_COMMAND",
  "message": "Cannot buy unit without sufficient gold",
  "status": 400,
  "timestamp": "2024-01-15T14:23:45Z",
  "requestId": "req_550e8400-e29b-41d4-a716-446655440000"
}
```

**Common error codes:**
- `400 Bad Request` — VALIDATION_ERROR, INVALID_COMMAND
- `401 Unauthorized` — UNAUTHORIZED, INVALID_TOKEN, TOKEN_EXPIRED
- `403 Forbidden` — FORBIDDEN, NOT_GAME_PARTICIPANT
- `404 Not Found` — NOT_FOUND, GAME_NOT_FOUND
- `409 Conflict` — CONFLICT, GAME_FULL, UNIT_PLACEMENT_CONFLICT
- `423 Locked` — LOCKED, ROUND_LOCKED
- `429 Too Many Requests` — RATE_LIMITED
- `500 Internal Server Error` — INTERNAL_ERROR

---

## Rate limiting

All endpoints are rate-limited:
- Default: 100 requests/minute per user
- Burst: 20 requests allowed in first 10 seconds

**Response headers:**
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87
X-RateLimit-Reset: 1705340625
```

When limit is exceeded:
```
HTTP 429 Too Many Requests
Retry-After: 30
```

---

## Status codes summary

| Code | Meaning |
|------|---------|
| `200` | Success (GET, POST without creation) |
| `201` | Created (POST with creation) |
| `204` | No content (successful action, no response body) |
| `400` | Bad request (validation error) |
| `401` | Unauthorized (not authenticated) |
| `403` | Forbidden (authenticated but not authorized) |
| `404` | Not found |
| `409` | Conflict (duplicate, incompatible state) |
| `423` | Locked (resource locked, cannot modify) |
| `429` | Rate limited |
| `500` | Internal server error |

---

## Headers

**Required for all authenticated requests:**
```
Authorization: Bearer <token>
```

**Recommended:**
```
Content-Type: application/json
Idempotency-Key: <UUID>  # For mutation endpoints
X-Request-ID: <UUID>     # For tracing (server may generate)
```

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

### Using OpenAPI/Swagger

Access interactive API docs:
```
http://localhost:8080/swagger-ui.html
```

or

```
http://localhost:8080/v3/api-docs
```

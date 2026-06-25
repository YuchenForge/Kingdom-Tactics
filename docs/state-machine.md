# Kingdom Tactics — State Machine

## High-Level State Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                    WAITING_FOR_PLAYERS                           │
│                  (Both players joined game)                       │
│                                                                  │
└───────────────────────────────────┬──────────────────────────────┘
                                    │
                                    │ Both players ready
                                    ▼
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                      PREPARATION (Round 1-8)                     │
│              (Players buy, place, lock their board)              │
│                    45-second timer per player                    │
│                                                                  │
│    ┌─────────────────────────────────────────────────────────┐  │
│    │ Player Actions:                                         │  │
│    │  • BUY_UNIT(shopSlot)                                  │  │
│    │  • REFRESH_SHOP()                                      │  │
│    │  • SELL_UNIT(unitId)                                  │  │
│    │  • PLACE_UNIT(unitId, x, y)                           │  │
│    │  • MOVE_UNIT(unitId, x, y)                            │  │
│    │  • LOCK_BOARD()                                       │  │
│    │                                                         │  │
│    │ Deadline actions (45s passed):                         │  │
│    │  • Auto-lock any unlocked player                      │  │
│    │  • Empty plan is valid (0 units is OK)                │  │
│    └─────────────────────────────────────────────────────────┘  │
│                                                                  │
└───────────────────────────────────┬──────────────────────────────┘
                                    │
                    Both players locked OR 45s deadline
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                         LOCKED                                   │
│          (Both players submitted final board state)              │
│                   Awaiting worker resolution                     │
│                                                                  │
└───────────────────────────────────┬──────────────────────────────┘
                                    │
                                    │ Worker picks up round
                                    ▼
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                       RESOLVING                                  │
│         (Background worker runs deterministic combat)            │
│                                                                  │
│    ┌─────────────────────────────────────────────────────────┐  │
│    │ Worker Process:                                         │  │
│    │  1. Acquire exclusive lock on round (row-level)        │  │
│    │  2. Generate deterministic combat seed                 │  │
│    │  3. Run combat simulation (tick loop)                  │  │
│    │  4. Calculate Keep damage                              │  │
│    │  5. Record combat events (MOVED, ATTACK, DIED, etc.)  │  │
│    │  6. Write round-end snapshot                           │  │
│    │  7. Release lock and transition to ROUND_RESULT        │  │
│    │                                                         │  │
│    │ Failure handling:                                       │  │
│    │  • Transient error → retry (exponential backoff)       │  │
│    │  • Persistent error → log and alert ops                │  │
│    └─────────────────────────────────────────────────────────┘  │
│                                                                  │
└───────────────────────────────────┬──────────────────────────────┘
                                    │
                    Combat simulation complete
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                     ROUND_RESULT                                 │
│        (Combat outcome determined; Keep damage applied)          │
│                                                                  │
│    ┌─────────────────────────────────────────────────────────┐  │
│    │ Apply Keep damage:                                      │  │
│    │  damage = 1 + unit_count + floor(total_hp / 10)        │  │
│    │                                                         │  │
│    │ Check win conditions (in order):                       │  │
│    │  1. If loser.keep_hp <= 0  →  FINISHED                │  │
│    │  2. If round = 8           →  FINISHED                │  │
│    │  3. Else                   →  PREPARATION (next round) │  │
│    │                                                         │  │
│    │ If continuing:                                         │  │
│    │  • Reset gold and units                                │  │
│    │  • Generate new shop                                   │  │
│    │  • Create new PREPARATION phase                        │  │
│    └─────────────────────────────────────────────────────────┘  │
│                                                                  │
└───────────────────────────────────┬──────────────────────────────┘
                                    │
                    Win condition met or round 8 complete
                                    │
                                    ▼
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│                      FINISHED                                    │
│            (Match over; winner and ratings determined)           │
│                                                                  │
│    ┌─────────────────────────────────────────────────────────┐  │
│    │ Winner determination:                                   │  │
│    │  1. Check who reached 0 Keep HP first                  │  │
│    │  2. If tied after round 8:                             │  │
│    │     a. Higher Keep HP → winner                         │  │
│    │     b. If tied, higher gold → winner                   │  │
│    │     c. If tied, → draw                                 │  │
│    │                                                         │  │
│    │ Actions:                                                │  │
│    │  • Award ratings (winner +25, loser −25)               │  │
│    │  • Store match in match_history                        │  │
│    │  • Make replay available                               │  │
│    │  • Offer rematch button                                │  │
│    └─────────────────────────────────────────────────────────┘  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## State Transition Rules

### WAITING_FOR_PLAYERS → PREPARATION

**Trigger:** Both players joined game, both marked ready

**Conditions:**
- `game_players.count() == 2`
- `game_players[0].is_ready == true`
- `game_players[1].is_ready == true`

**Actions:**
- Generate round #1 shop
- Award starting gold (10)
- Create shop_offers for both players
- Set planning deadline to `now + 45 seconds`
- Publish event: `GAME_STARTED`

**Next state:** PREPARATION

---

### PREPARATION → LOCKED

**Trigger:** Both players lock board OR 45-second deadline expires

**Conditions (early exit):**
- `round_plans[player0].is_locked == true`
- `round_plans[player1].is_locked == true`

**Conditions (deadline):**
- `now >= planning_deadline` (server time, not client)
- Both unlocked boards auto-lock with current state

**Actions:**
- Record final plan state for both players
- Mark both plans as locked
- Persist round_plans and commands to database
- Publish event: `BOARD_LOCKED` (private to each player: show opponent's unit count only, not positions)

**Next state:** LOCKED

---

### LOCKED → RESOLVING

**Trigger:** Worker picks up locked round

**Conditions:**
- Round is in LOCKED state
- Worker acquires exclusive lock on the round (row-level locking in DB)

**Actions:**
- Transition state to RESOLVING
- Worker begins combat simulation in background

**Next state:** RESOLVING

---

### RESOLVING → ROUND_RESULT

**Trigger:** Combat simulation completes successfully

**Conditions:**
- Worker finishes tick loop (all units dead or 160 ticks elapsed)
- Worker calculates Keep damage
- Worker writes combat events to database

**Actions:**
- Persist combat events (UNIT_MOVED, ATTACK, UNIT_DIED, etc.)
- Persist round-end snapshot
- Release row-level lock
- Transition to ROUND_RESULT
- Publish event: `ROUND_RESOLVED` (visible to both players)

**Next state:** ROUND_RESULT

---

### ROUND_RESULT → PREPARATION (continue to next round)

**Trigger:** Round outcome: no winner yet, and round < 8

**Conditions:**
- Neither player's Keep HP <= 0
- Current round < 8

**Actions:**
- Apply Keep damage to losing player (Keep HP -= damage)
- Reset gold: `gold = min(gold + 5, 20)` (cap at 20)
- Clear board: remove all units from both boards (benches keep gold cost value)
- Generate new shop for next round
- Increment round_number
- Set new planning deadline
- Create PREPARATION state for next round
- Publish event: `ROUND_FINISHED`

**Next state:** PREPARATION (round N+1)

---

### ROUND_RESULT → FINISHED (winner determined: Keep HP <= 0)

**Trigger:** One player's Keep HP drops to 0 or below

**Conditions:**
- Player A Keep HP <= 0 (after Keep damage applied)
- Player B Keep HP > 0

**Actions:**
- Declare Player B as winner
- Record match result: winner_id, loser_id, final_round
- Award rating delta: winner +25, loser −25
- Persist match to database
- Mark game as finished
- Publish event: `GAME_FINISHED` (visible to both)

**Next state:** FINISHED

---

### ROUND_RESULT → FINISHED (tie-breaker: round 8 complete)

**Trigger:** Round 8 ends without a Keep HP = 0 condition

**Conditions:**
- Current round == 8
- Both players Keep HP > 0

**Actions:**
- Compare Keep HP: higher wins
- If Keep HP tied:
  - Compare gold: higher wins
  - If gold tied: declare draw
- Record match result: winner_id (or null if draw), final_round = 8
- Award rating delta (if not draw)
- Persist match to database
- Publish event: `GAME_FINISHED`

**Next state:** FINISHED

---

## Concurrency and Locking

### Planning phase (PREPARATION)

- **No locking:** Players can issue commands concurrently
- **Conflict resolution:** Last write wins for PLACE/MOVE; buys/sells are transactional
- **Idempotency:** Idempotency-Key prevents duplicate command effects

### Resolution phase (RESOLVING)

- **Row-level lock:** Worker acquires lock on `rounds(game_id, round_number)`
- **Mutual exclusion:** Only one worker can resolve a round at a time
- **Lock release:** On success or fatal error (after retry attempts)
- **Orphaned rounds:** Timeout mechanism (if worker dies, round is re-queued after 5 minutes)

---

## Invalid state transitions

The following transitions are **not allowed** and should be rejected:

| From | To | Reason |
| --- | --- | --- |
| PREPARATION | PREPARATION | Cannot loop; must go LOCKED |
| LOCKED | PREPARATION | Cannot go backward |
| RESOLVING | PREPARATION | Cannot go backward |
| ROUND_RESULT | RESOLVING | Cannot re-resolve |
| FINISHED | PREPARATION | Match is over; can only rematch (new game) |

If an invalid transition is attempted:
1. Log error with game_id and current state
2. Respond with HTTP 409 Conflict
3. Alert operations (may indicate a bug)

---

## Timeout and cleanup

### Orphaned rounds (LOCKED or RESOLVING > 5 minutes)

```
Worker checklist every 1 minute:
  for each round in LOCKED or RESOLVING state:
    if round_last_updated < now - 5 minutes:
      log(WARN, "Orphaned round detected", round_id)
      reset to LOCKED state (release lock)
      queue for re-resolution
```

### Abandoned players (PREPARATION > 45 seconds without second lock)

```
Deadline monitor every 10 seconds:
  for each game in PREPARATION state:
    if planning_deadline <= now:
      for each unlocked player:
        lock_board(player_id, current_board_state)
```

---

## Example game flow

```
Time  Game     Round  State                     P1 HP  P2 HP  Event
----  -------  -----  -----------------------  -----  -----  -----------
T00   game#1   —      WAITING_FOR_PLAYERS      —      —
T05                   (P1 joined, waiting)
T10                   (P2 joined)
T10                   PREPARATION (round 1)    20     20     GAME_STARTED
T20                   PREPARATION              20     20     (planning...)
T50                   LOCKED                   20     20     BOARD_LOCKED
T51                   RESOLVING                20     20     (worker resolving)
T53                   ROUND_RESULT             20     15     ROUND_RESOLVED
T53                   PREPARATION (round 2)    20     15     (reset for round 2)
...
T180  (round 8 completes)
T180                  ROUND_RESULT             12     8      (P1 winning)
T180                  FINISHED                 12     8      GAME_FINISHED (P1 wins)
```

---

## State monitoring and debugging

### Queries for operators

```sql
-- Find games stuck in PREPARATION (> 1 hour)
SELECT game_id, round_number, state, created_at
FROM games
WHERE state = 'PREPARATION' AND created_at < now() - '1 hour'::interval;

-- Find orphaned RESOLVING rounds
SELECT round_id, last_updated
FROM rounds
WHERE state = 'RESOLVING' AND last_updated < now() - '5 minutes'::interval;

-- Summary of active games by state
SELECT state, COUNT(*) as count
FROM games
WHERE finished_at IS NULL
GROUP BY state;
```

### Health check endpoint

```
GET /health/games

{
  "total_active_games": 42,
  "by_state": {
    "PREPARATION": 15,
    "LOCKED": 8,
    "RESOLVING": 3,
    "ROUND_RESULT": 16,
  },
  "oldest_preparation": "5 minutes ago",
  "orphaned_rounds": 0
}
```

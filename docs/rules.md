# Kingdom Tactics — Game Rules

## 1. Overview

**Kingdom Tactics** is a 1v1 turn-based tactical auto-battler with server-authoritative combat resolution.

- Players recruit units from a rotating shop
- Place units on a grid and lock their formation
- Backend runs deterministic combat simulation
- Players watch the outcome and either rematch or finish

## 2. Match Format

### Players and objectives

- **2 players** compete per match
- Each player defends a **Keep** with 20 HP
- **Goal:** reduce opponent's Keep HP to 0, or have higher Keep HP after round 8
- **Tie-breaker:** remaining gold, then draw

### Board layout

- **4 × 4 grid** per player
- **Planning zone:** rows 0–1 (closer to Keep); units placed here cannot move
- **Battle zone:** rows 2–3 (closer to opponent); units can move freely
- Units cannot move off the board

### Time and resource constraints

| Parameter         | Value |
| ----------------- | ----- |
| Match length      | 8 rounds |
| Planning timer    | 45 seconds |
| Combat time limit | 40 seconds |
| Starting Keep HP  | 20 |
| Starting gold     | 10 |
| Gold per round    | +5 (capped at 20) |
| Initial unit cap  | 3 units |
| Late-game unit cap| 5 units (rounds 5+) |

---

## 3. Round Flow

Each round follows this strict sequence:

```
ROUND_START
├─ Phase: PREPARATION
│  ├─ Generate shop from deterministic seed
│  ├─ Grant gold (+5, capped at 20)
│  ├─ Players spend 45 seconds:
│  │  ├─ BUY units from shop
│  │  ├─ SELL units to bench
│  │  ├─ PLACE / MOVE units on board
│  │  └─ LOCK board (submit final plan)
│  │
│  ├─ Actions on deadline:
│  │  ├─ Any unlocked board → lock with current state
│  │  └─ Empty plan → empty board for combat
│  │
│  └─ Both players locked
│
├─ Phase: RESOLVING
│  ├─ Queue round for worker
│  ├─ Worker acquires lock and begins resolution
│  ├─ Generate deterministic combat seed
│  ├─ Run combat simulation (deterministic tick loop)
│  ├─ Record all events and round-end snapshot
│  └─ Transition to ROUND_RESULT
│
├─ Phase: ROUND_RESULT
│  ├─ Notify both players of outcome
│  ├─ Apply Keep damage
│  ├─ Check win condition:
│  │  ├─ If loser.keep_hp ≤ 0 → GAME_FINISHED (winner = other player)
│  │  ├─ If round = 8 → GAME_FINISHED (winner = higher keep_hp or draw)
│  │  └─ Else → PREPARATION (next round)
│  │
│  └─ Persist game state snapshot
│
└─ GAME_FINISHED
   ├─ Record winner and ratings delta
   └─ Match available for replay
```

---

## 4. Economy

### Gold flow

| Event              | Gold change |
| ------------------ | ----------- |
| Game start         | +10 |
| Each round         | +5 (capped at 20) |
| Refresh shop       | −1 |
| Sell unit          | +1 × unit_cost |
| Buy unit           | −1 × unit_cost |

### Gold constraints

- Gold cannot go below 0
- Gold is capped at 20
- A player cannot buy if cost exceeds current gold
- Refresh is free if shop has no units

### Bench

- Unlimited bench size
- Benched units take no actions in combat
- Benched unit is still "owned" and cannot be accessed by opponent
- Sell unit: unit returned to bench, and 1 gold refunded per unit cost

---

## 5. Unit Definitions

All units attack with a cooldown (attack speed is 1 attack per second unless otherwise specified).

| Unit         | Cost | HP | ATK | RNG | Special ability              |
| ------------ | ---: | -: | --: | --: | ----------------------------- |
| Squire       |    1 |  8 |   2 |  1 | None (basic melee) |
| Shieldbearer |    2 | 16 |   1 |  1 | Reduces all damage by 1 (minimum 1) |
| Ranger       |    2 |  7 |   4 |  3 | Targets lowest-HP enemy in range |
| Knight       |    3 | 18 |   5 |  1 | Targets nearest enemy (by Manhattan distance) |
| Mage         |    3 |  9 |   6 |  3 | Every 3rd attack: splash damage to orthogonal neighbors |
| Healer       |    3 | 10 |   1 |  2 | Every 3rd action: heals lowest-HP ally in range by 5 |

### Unit abilities explained

**Shieldbearer:** Reduces incoming damage by 1, but minimum damage is always 1.
```
damage = max(1, attacker.attack - defender.armor - 1)
       = max(1, attacker.attack - 1) for Shieldbearer
```

**Ranger:** Scans all enemies within range 3, targets the one with lowest current HP. Ties broken by unit ID.

**Knight:** Always targets the nearest enemy by Manhattan distance. Ties broken by lowest HP, then unit ID.

**Mage:** Counts actions (both attacks and passes). Every 3rd action is an attack that deals damage to the target plus all orthogonal neighbors (4 squares: N, S, E, W) within 1 square. Does not damage self.

**Healer:** Counts actions. Every 3rd action, heals the lowest-HP allied unit in range 2 (including self) by 5 HP. Healing cannot exceed max HP. If tied, heals lowest unit ID.

---

## 6. Planning Commands

Players submit commands during the 45-second PREPARATION phase.

### Command: BUY_UNIT

```
BUY_UNIT(shop_slot: 0-4)
```

- Slot is 0–4 (5 offerings per round)
- Cost is deducted from gold
- Unit is placed on bench
- Empty slot remains empty (no auto-fill during planning)
- Cannot buy if gold < unit cost
- Cannot exceed bench/board capacity

### Command: REFRESH_SHOP

```
REFRESH_SHOP()
```

- Costs 1 gold
- Discards current shop and generates a new one
- New shop is still deterministic (different seed)
- Cannot refresh with gold < 1
- Free if all 5 shop slots are empty

### Command: SELL_UNIT

```
SELL_UNIT(unit_id)
```

- Unit must be on board or bench
- Unit is removed
- Refund = 1 × unit.cost
- Gold is credited immediately

### Command: PLACE_UNIT

```
PLACE_UNIT(unit_id, x, y)
```

- Unit must be on bench
- Destination must be in planning zone (y = 0 or 1)
- Destination must be empty
- Unit moves from bench to board
- Cannot exceed board unit cap (3 initially; 5 in round 5+)

### Command: MOVE_UNIT

```
MOVE_UNIT(unit_id, x, y)
```

- Unit must be on board
- Destination must be in planning zone (y = 0 or 1)
- Destination must be empty
- Only units already on the board can be moved
- Cannot exceed board unit cap

### Command: LOCK_BOARD

```
LOCK_BOARD()
```

- Submits final board state
- Player's planning is locked; no further commands accepted
- Server awaits both players' locks or 45-second deadline
- Once locked, board is frozen for this round's combat

---

## 7. Combat Resolution

### Tick-based simulation

- Combat runs at a **250 ms logical tick** (4 ticks/second)
- Each tick:
  1. Decrement cooldowns for all units
  2. For each unit (in deterministic order):
     - If cooldown = 0 and unit is alive:
       - Select target (see target selection rules)
       - If target in range: attack
       - If target not in range: move toward target
  3. Apply damage and death checks
  4. Remove dead units
- Combat ends when:
  - One board has no surviving units, OR
  - 40 seconds (160 ticks) have elapsed

### Target selection

Each unit prioritizes targets in this order:

1. **In range?** If yes, pick a target. If no, skip to movement.
2. **Range check:** Range is Chebyshev distance (max of |Δx| and |Δy|)
3. **Target priority:** Depends on unit type:
   - **Squire, Shieldbearer, Knight:** Nearest enemy (by Manhattan distance)
   - **Ranger:** Lowest current HP
   - **Mage, Healer:** Lowest current HP
4. **Tie-break order** (applied in sequence):
   - Shortest Manhattan distance
   - Lowest current HP
   - Lowest stable unit ID (chronological order of unit creation)

### Movement

- Units move **one orthogonal tile per movement action** (north, south, east, west)
- Cannot move off the board
- Cannot move through occupied tiles
- Cannot move off-board; blocked movement = no action that tick
- Path-finding is greedy: move one step closer to target by Manhattan distance

### Attack and damage

```
damage = max(1, attacker.attack - defender.armor)
```

where `armor = 1` for Shieldbearer, else `armor = 0`.

- Minimum damage is always 1
- Splash attacks (Mage) hit all orthogonal neighbors in range 1
- Splash ignores armor
- Healing (Healer) is capped at max HP

### Keep damage

When a board has no surviving units, the opponent's Keep takes damage:

```
keep_damage = 1
            + count(surviving enemy units)
            + floor(total_hp(surviving enemy units) / 10)
```

Example:
- 0 units on winning board → damage = 1
- 3 units, 25 total HP → damage = 1 + 3 + 2 = 6

---

## 8. Game States and Transitions

### State machine

```
WAITING_FOR_PLAYERS
    ↓
PREPARATION (round 1, 45-second timer)
    ↓
LOCKED (both players submitted plans, waiting for worker)
    ↓
RESOLVING (worker running combat simulation)
    ↓
ROUND_RESULT (outcome known, Keep damage applied)
    ├─ If loser.keep_hp ≤ 0  →  FINISHED (game over)
    ├─ If round = 8           →  FINISHED (game over)
    └─ Else                   →  PREPARATION (next round)
    ↓
FINISHED (match over, winner determined)
```

### State fields

Each game tracks:

- `game_id`: UUID
- `state`: current phase
- `round_number`: 1–8
- `players`: [player1, player2]
- `players[i].keep_hp`: 0–20
- `players[i].gold`: 0–20
- `players[i].board`: 4×4 grid of unit IDs or null
- `players[i].bench`: list of unit IDs
- `players[i].shop`: list of unit definitions
- `players[i].plan`: locked copy of commands (hidden from opponent until round resolves)
- `round_deadline`: absolute timestamp (server time)
- `is_human_controlled[i]`: true if real player, false if bot/AI

---

## 9. Determinism and Replay

### What is stored per round

Each round record includes:

```
{
  game_id,
  round_number,
  rules_version,      // e.g., "1.0" for all MVP rounds
  combat_seed,        // deterministic seed for RNG
  round_start_snapshot: {
    keep_hp: [20, 20],
    gold: [15, 15],
    board: [ [...], [...] ],
    bench: [ [...], [...] ],
    shop: [ [...] ]
  },
  player_commands: [
    [ COMMAND, COMMAND, ... ],  // player 0
    [ COMMAND, COMMAND, ... ]   // player 1
  ],
  combat_events: [
    { type: "UNIT_PLACED", unit_id, x, y, tick },
    { type: "UNIT_MOVED", unit_id, x, y, tick },
    { type: "ATTACK", attacker_id, target_id, damage, tick },
    { type: "HEALED", healer_id, target_id, amount, tick },
    { type: "UNIT_DIED", unit_id, tick },
    { type: "COMBAT_ENDED", reason, tick },
    ...
  ],
  round_end_snapshot: {
    keep_hp: [20, 14],
    surviving_units: [ [...], [...] ],
    keep_damage_dealt: 6
  }
}
```

### Determinism guarantee

**Same input → identical output.** If you replay a round with:
- Same rules version
- Same combat seed
- Same board state at start of combat
- Same unit definitions
- Same target selection tie-breaks

Then the combat must produce:
- Identical sequence of events
- Identical final board state
- Identical Keep damage

This enables:
- Exact replay from stored events
- Bug reproduction
- Rules version migrations (v1 → v2 with validation)
- Balance simulations
- Efficient unit tests

---

## 10. Win Conditions and Tie-Breaking

### Primary win conditions (checked in order)

1. **Opponent's Keep HP ≤ 0** → Current player wins immediately
2. **Round 8 complete** → Higher Keep HP wins
3. **Both Keep HP equal after round 8** → Tie-breaker

### Tie-breaker (if both Keep HP equal after round 8)

1. **Higher gold** → gold winner wins
2. **Both gold equal** → Draw

### Example outcomes

| Scenario | Winner |
| -------- | ------ |
| P1 Keep = 5, P2 Keep = 0, Round 3 | P1 (immediate) |
| P1 Keep = 8, P2 Keep = 12, Round 8 | P2 (higher Keep) |
| P1 Keep = 10, P2 Keep = 10, Gold P1 = 15, Gold P2 = 12, Round 8 | P1 (higher gold) |
| P1 Keep = 10, P2 Keep = 10, Gold P1 = 8, Gold P2 = 8, Round 8 | Draw |

---

## 11. Special Cases and Edge Cases

### Empty board in combat

- If a player's board is empty (0 units), that side loses the round immediately
- Opponent takes damage: 1 + 0 + 0 = 1 (minimum)

### Simultaneous deaths

- If unit A kills unit B on the same tick, and unit B's counterattack would kill A:
  - Both units die on that tick
  - Neither receives credit
  - Both are removed at end of tick

### Healer at max HP

- If lowest-HP ally is at max HP, Healer's 3rd action does nothing but still counts toward the next 3-action cycle

### Movement when surrounded

- If a unit cannot move toward the target (all adjacent tiles occupied), it does not move but still waits for its next tick
- It may attack if a target becomes in-range later

### Out-of-order action resolution

- Within a tick, all units act in deterministic order (by unit ID, ascending)
- Cooldown is decremented before action
- Actions are resolved and damage applied immediately
- Dead units are removed at end of tick, then don't act

---

## 12. Glossary

- **Board:** The 4×4 grid where combat happens
- **Bench:** Off-board storage for recruited but unplaced units
- **Keep:** The defending structure with 20 HP (goal is to reduce to 0)
- **Planning zone:** Rows 0–1 (units placed here stay in place during combat)
- **Battle zone:** Rows 2–3 (units placed here can move freely)
- **Unit cap:** Maximum number of units allowed on board (3 initially, 5 in round 5+)
- **Cooldown:** Countdown timer for when a unit can act again (starts at 4 ticks = 1 second)
- **In range:** Target within Chebyshev distance ≤ unit.range
- **Manhattan distance:** |Δx| + |Δy|
- **Deterministic:** Same input always produces same output
- **Replay:** Re-running stored events to verify combat outcome or inspect unit behavior
- **Idempotency:** Sending the same command twice has the same effect as sending it once

---

## 13. Rules Versions and Migrations

All rounds include a `rules_version` field. This enables:

- Safe rules changes without breaking replays
- Version migrations (e.g., "rebalance Mage splash range")
- A/B testing different rule sets
- Backward compatibility when reading old match data

When implementing a new rules version, you must:

1. Update the unit definitions or mechanics
2. Increment `rules_version` string (e.g., "1.0" → "1.1")
3. Write a migration test comparing old vs. new outcomes
4. Store the old rule set in the codebase for replaying historical matches

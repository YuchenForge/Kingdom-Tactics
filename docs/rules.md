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

Each player maintains a **separate 4 × 4 placement board** during planning. Boards are not shared until combat begins.

| Area | Size | Purpose |
| ---- | ---- | ------- |
| Placement board | 4 × 4 per player | Units placed here during planning (any cell) |
| Holding lane | 5 slots per player | Units bought from shop, not yet placed |
| Combat board | 4 × 8 merged | Both placement boards stacked vertically (P0 rotated 180°) during combat only |

**Placement board (local coordinates):**
- Any empty cell `(x, y)` where `x, y ∈ [0, 3]` is valid for placement or movement during planning
- Units cannot be placed off the board

**Board lifecycle per round:**
1. **PREPARATION:** Each player arranges units on their own 4×4 board (and holding lane)
2. **Combat start:** Both boards merge into one 4×8 combat board (Player 0 rotated 180°); every unit enters combat at **full HP**
3. **Round end:** All units from each player's **locked formation** return to their placement board at **full HP** (combat deaths and damage do not carry over)

**Holding lane:**
- Fixed size of **5 slots** per player (slots `0–4`)
- Units purchased from the shop go to the first empty lane slot
- Lane units take no combat actions
- Cannot buy if the lane is full (all 5 slots occupied)
- Multiple copies of the same `(type, level)` may sit in the lane (see [Unit levels and merging](#unit-levels-and-merging))

### Coordinate system

Players always use **local coordinates** on their own placement board: `x ∈ [0, 3]`, `y ∈ [0, 3]`.

When combat starts, the two boards merge into a single **4 × 8 combat board** (stacked vertically, chess perspective — each player faces the opponent):

```
Combat board (global coordinates)

  y=0  [ player 0 — rows 0–3 (top half, rotated 180°) ]
  y=1  [                                               ]
  y=2  [                                               ]
  y=3  [                                               ]
  y=4  [ player 1 — rows 4–7 (bottom half, unchanged) ]
  y=5  [                                               ]
  y=6  [                                               ]
  y=7  [                                               ]
       x=0   1   2   3
```

**Mapping from local → global combat coordinates:**
- **Player 0 (top):** board is rotated **180°** before merge — row and column order reversed:
  - `global_x = 3 - local_x`
  - `global_y = 3 - local_y` (rows 0–3)
- **Player 1 (bottom):** unchanged:
  - `global_x = local_x`
  - `global_y = local_y + 4` (rows 4–7)

**Mapping from global → local placement coordinates:**
- **Player 0:** `local_x = 3 - global_x`, `local_y = 3 - global_y` (when `global_y ∈ [0, 3]`)
- **Player 1:** `local_x = global_x`, `local_y = global_y - 4` (when `global_y ∈ [4, 7]`)

**Example:** Player 0 Squire at local `(2, 0)` → combat `(1, 3)`; Player 1 Squire at local `(1, 2)` → combat `(1, 6)`.

**Storage indices (row-major):**
- Placement cell index: `index = y * 4 + x` (0–15)
- Combat cell index: `index = global_y * 4 + global_x` (0–31)
- Lane slot index: `0–4`

Combat events, movement, range checks, and targeting all use **global combat coordinates**. Planning command `RELOCATE_UNIT` uses **local placement coordinates** (board cells) or lane slot indices.

### Time and resource constraints

| Parameter         | Value |
| ----------------- | ----- |
| Match length      | 8 rounds |
| Planning timer    | 45 seconds |
| Combat time limit | 40 seconds |
| Starting Keep HP  | 20 |
| Starting gold     | 10 (round 1 only) |
| Gold per later round | +5 added to remaining gold when the next round starts (no cap) |
| Shop offers / round | 3 (slots `0–2`) |
| Initial unit cap  | 3 units |
| Late-game unit cap| 5 units (rounds 5+) |

---

## 3. Round Flow

Each round follows this strict sequence:

```
ROUND_START
├─ Phase: PREPARATION
│  ├─ Generate shop from deterministic seed
│  ├─ Gold: round 1 starts at 10; rounds 2–8 grant +5 on remaining gold (no cap)
│  ├─ Players spend 45 seconds:
│  │  ├─ BUY units from shop (may trigger auto-merge)
│  │  ├─ SELL units from board or lane
│  │  ├─ RELOCATE units (lane ↔ board, reorder lane, reposition board)
│  │  └─ LOCK board (submit final plan)
│  │
│  ├─ Actions on deadline:
│  │  ├─ Any unlocked board → auto-lock with current state
│  │  └─ Empty plan is valid (0 units OK)
│  │
│  └─ Both locked (manual or deadline) → LOCKED → combat resolution
│  │  └─ Empty plan → empty board for combat
│  │
│  └─ Both players locked
│
├─ Phase: RESOLVING
│  ├─ Worker TX 1: claim LOCKED round (`FOR UPDATE SKIP LOCKED`) →
│  │    set RESOLVING, persist combat_seed, **commit** (no lock held afterward)
│  ├─ Merge both locked placement boards into 4×8 combat board
│  ├─ Run combat simulation outside any DB transaction (deterministic tick loop)
│  └─ Worker TX 2 (atomic): persist events + combat-end snapshots +
│       apply Keep damage + record outcome → transition to ROUND_RESULT
│
├─ Phase: ROUND_RESULT
│  ├─ Durable checkpoint: resolution data and Keep damage already committed in TX 2
│  ├─ Play recorded combat on the persisted server schedule
│  ├─ Wait for presentation to end before advancing
│  ├─ Worker TX 3: check win conditions (after Keep damage already applied):
│  │  ├─ Both Keeps ≤ 0 → FINISHED (match draw)
│  │  ├─ Exactly one Keep ≤ 0 → FINISHED (other wins)
│  │  ├─ If round = 8 → FINISHED (higher Keep HP, else gold, else draw)
│  │  └─ Else → PREPARATION (next round)
│  │
│  └─ If continuing: copy locked formations at full HP, gold +5, new shops
│
└─ FINISHED
   ├─ Record winner and ratings delta (±25; draw unchanged) in TX 3
   └─ Historical events and round results remain available to participants
```

---

## 4. Economy

### Gold flow

| Event              | Gold change |
| ------------------ | ----------- |
| Round 1 start      | Set to **10** |
| Round N start (N≥2)| **+5** on remaining gold from previous round |
| Refresh shop       | −1 (always; never free) |
| Sell unit          | + `base_cost × level` |
| Buy unit           | − `base_cost` (always Level 1 from shop) |

### Gold constraints

- Gold cannot go below 0
- **No gold cap** — sells and round grants may raise gold without an upper bound
- A player cannot buy if cost exceeds current gold
- Refresh always costs 1 gold (even if all shop slots are empty)

### Holding lane

- Fixed size of **5 slots** per player
- Units purchased from the shop are placed in the first empty lane slot
- Lane units take no actions in combat
- Lane units are still "owned" and cannot be accessed by the opponent
- **Duplicates allowed:** multiple copies of the same `(type, level)` may occupy the lane (and the board)
- Sell unit: unit removed from board or lane; refund = `base_cost × level` (see [Sell refund](#sell-refund-by-level))
- Cannot buy if lane is full (all 5 slots occupied)

---

## 5. Unit Definitions

All units act with a cooldown (action speed is 1 action per second unless otherwise specified).

| Unit         | Cost | HP | ATK | RNG | Special ability              |
| ------------ | ---: | -: | --: | --: | ----------------------------- |
| Squire       |    1 |  8 |   2 |  1 | None (basic melee) |
| Shieldbearer |    2 | 16 |   1 |  1 | Reduces direct damage by 1 (minimum 1); Mage splash ignores armor |
| Ranger       |    2 |  7 |   4 |  3 | Targets lowest-HP enemy in range |
| Knight       |    3 | 18 |   5 |  1 | Targets nearest enemy (by Manhattan distance) |
| Mage         |    3 |  9 |   6 |  3 | Every 3rd attack: splash damage to orthogonal neighbors |
| Healer       |    3 | 10 |   1 |  2 | Every 3rd attack: heals lowest-HP ally (amount by level; board-wide; no enemy in range required) |

Table values are **Level 1 (base)** stats. Higher levels use HP-biased stat tables and a Healer heal curve (see [Unit levels and merging](#unit-levels-and-merging)). Range, armor, targeting rules, and special-ability cadence do not change with level.

### Unit levels and merging

Units can be **leveled up** by merging duplicate copies. This is a **planning-phase** mechanic only; the combat engine receives units with their **resolved stats** already applied.

#### Levels

| Level | Copies required to create | Display |
| ----- | ------------------------- | ------- |
| 1     | — (shop default)          | ★       |
| 2     | 3× Level 1 same type      | ★★      |
| 3     | 3× Level 2 same type      | ★★★     |

- Maximum level is **3**
- Level persists across rounds (upgraded units keep their level until sold)
- Each leveled unit counts as **one** unit toward the board unit cap

#### Merge rule

When a player owns **three or more** units with the same **type** and **level** (counted across holding lane **and** placement board), **one merge** runs immediately: three copies are consumed and replaced by **one** unit of **level + 1**:

```
3× (Squire, Level 1)  →  1× (Squire, Level 2)
3× (Knight, Level 2)  →  1× (Knight, Level 3)
```

- Merge is **automatic** — no separate player command
- **When to check:** immediately after each successful `BUY_UNIT` (and again after merge if copies still remain, e.g. 6× Level 1 → two merges)
- **Counting:** lane + board together; `RELOCATE_UNIT` does not change total owned count (only moves copies between lane/board)
- The three consumed copies are removed; one upgraded copy remains
- **Placement priority:** if any consumed copy was on the board, the upgraded unit appears on the board at the position of the consumed copy with the **lowest unit ID**; otherwise it occupies the **first lane slot** freed by the merge
- Cannot merge past Level 3 (three Level 3 copies do not combine)

#### Holding lane and duplicates

The lane and board both hold **individual copies**. There is no lane uniqueness rule — only the **5-slot capacity** and the **3-copy merge** limit growth.

**Example A (3rd buy, copies split across lane and board):**
1. Two Level 1 Squires on the board; lane empty
2. Buy a Level 1 Squire → it enters the lane → **3 copies total** (1 lane + 2 board)
3. **Merge runs immediately** → one Level 2 Squire remains (on the board at the lowest-ID consumed position)

**Example B (3 copies all in lane):**
1. Buy three Level 1 Squires without placing → lane holds 3× Level 1 Squire
2. **Merge runs on the 3rd buy** → one Level 2 Squire in the lane

**Example C (reach 3 via placement, then buy):**
1. Lane: 2× Level 1 Squire; board: 0
2. `RELOCATE_UNIT` one Squire to the board → board: 1, lane: 1 (still 2 copies — no merge)
3. Buy another Level 1 Squire → lane: 2, board: 1 → **3 copies** → **merge on buy**

#### Stat scaling by level

Stats are **not** `base × level`. Leveling favors **HP over ATK** so fights stay longer while upgrades remain valuable but not overwhelming.

**Design targets** (approximate overall strength vs Level 1, using `strength ≈ HP + 4×ATK`):
- Level 2 ≈ **+35%** stronger than Level 1
- Level 3 ≈ **+85%** stronger than Level 1

| Unit         | L1 HP / ATK | L2 HP / ATK | L3 HP / ATK |
| ------------ | ----------- | ----------- | ----------- |
| Squire       | 8 / 2       | 12 / 3      | 17 / 3      |
| Shieldbearer | 16 / 1      | 24 / 1      | 34 / 1      |
| Ranger       | 7 / 4       | 11 / 5      | 18 / 6      |
| Knight       | 18 / 5      | 27 / 6      | 38 / 8      |
| Mage         | 9 / 6       | 16 / 7      | 25 / 9      |
| Healer       | 10 / 1      | 15 / 1      | 22 / 1      |

**Ability scaling:**
- **Range** never increases
- **Shieldbearer** damage reduction stays **1** at all levels
- **Healer** heal amount: Level 1 → **5**, Level 2 → **7**, Level 3 → **10**
- **All other specials** (targeting, Mage splash cadence, etc.) unchanged; splash uses the unit's current ATK
- Special cadence (every 3rd **attack**) is unchanged

#### Sell refund by level

```
sell_refund = base_cost × level
```

| Unit         | L1 refund | L2 refund | L3 refund |
| ------------ | --------- | --------- | --------- |
| Squire       | 1         | 2         | 3         |
| Shieldbearer | 2         | 4         | 6         |
| Ranger       | 2         | 4         | 6         |
| Knight       | 3         | 6         | 9         |
| Mage         | 3         | 6         | 9         |
| Healer       | 3         | 6         | 9         |

#### Combat interaction

- Combat does **not** perform merges or level-up logic
- At combat start, each `UnitInstance` carries its **level** and **resolved** `maxHp` / `attack` / heal amount
- `CombatBoard.merge()` still resets every unit to **full HP** (using `effective_max_hp`)
- Round-end board restore preserves **level** and position; HP is reset to full for the next planning phase

#### Implementation note

Shop buys, sells, and automatic merges are implemented in the planning engine. Combat receives units that already have their level and resolved stats.

### Unit abilities explained

**Shieldbearer:** Reduces direct attack damage by 1, but minimum damage is always 1. Mage splash ignores armor.
```
damage = max(1, attacker.attack - defender.armor)
```
where `armor = 1` for Shieldbearer, else `armor = 0`.

**Ranger:** Among enemies within Chebyshev range 3, targets the one with lowest current HP. Tie-break: Manhattan distance, then unit ID. If no enemy is in range, selects lowest-HP enemy among all living enemies for movement.

**Knight:** Always targets the nearest enemy by Manhattan distance. Ties broken by lowest HP, then unit ID.

**Mage:** Counts attacks only (movement does not increment the counter). Every 3rd attack deals normal damage to the target **plus** splash damage to enemies in the target’s orthogonally adjacent cells (N, S, E, W), centered on the target even if the direct hit kills it. The primary target is hit only once; allies and diagonal neighbors are excluded. Splash uses full ATK and ignores armor. Does not damage self. The counter does not reset; splash also triggers on the 6th, 9th, … attack.

**Healer:** Counts attacks only (movement does not increment the counter). Every 3rd attack heals the lowest-HP allied unit on the board by the level heal amount (5 / 7 / 10) instead of dealing damage (no range limit on heal; **no enemy in range required** on heal turns). Healing cannot exceed max HP. If tied, heals lowest unit ID. The counter does not reset; heal also triggers on the 6th, 9th, … attack.

---

## 6. Planning Commands

Players submit commands during the 45-second PREPARATION phase.

### Command: BUY_UNIT

```
BUY_UNIT(shop_slot: 0-2)
```

- Slot is 0–2 (**3 offerings per round**)
- Shop always sells **Level 1** copies
- Cost is deducted from gold (`base_cost` of the unit type)
- Unit is placed in the first empty lane slot
- Empty shop slot remains empty (no auto-fill during planning)
- Cannot buy if gold < unit cost
- Cannot buy if holding lane is full (5/5 slots occupied)
- After a successful buy, run **auto-merge** if the player now owns **3+** copies of that `(type, level)` anywhere on lane + board

### Command: REFRESH_SHOP

```
REFRESH_SHOP()
```

- Costs 1 gold (always)
- Discards current shop and generates a new one (still **3** offers)
- New shop is still deterministic (different seed)
- Cannot refresh with gold < 1

### Command: SELL_UNIT

```
SELL_UNIT(unit_id)
```

- Unit must be on board or in holding lane
- Unit is removed
- Refund = `base_cost × level` (see [Sell refund by level](#sell-refund-by-level))
- Gold is credited immediately

### Command: RELOCATE_UNIT

```
RELOCATE_UNIT(unit_id, destination)
destination = Board(x, y) | Lane(slot)
```

One command covers all unit movement during planning:

| From → To | Destination | Notes |
|-----------|-------------|--------|
| Lane → Board | `Board(x, y)` | Empty cell; board unit cap applies (3; 5 in round 5+) |
| Board → Board | `Board(x, y)` | Empty cell (or same cell); cap unchanged |
| Lane → Lane | `Lane(slot)` | Explicit slot `0..4`; target empty (or same slot) |
| Board → Lane | `Lane(slot)` | Explicit slot `0..4`; target empty |

- Unit must exist on the player's lane or board
- Board coords are local placement coordinates (`x, y ∈ [0, 3]`)
- Does **not** trigger auto-merge (merge runs only after `BUY_UNIT`)

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

### Board merge and round reset

**Merge (combat start):** Before the tick loop runs, each player's placement board is merged into a single **4 × 8 combat board**. Player 1 occupies the lower half unchanged. Player 0's board is **rotated 180°** (reverse row and column order — chess perspective, facing the opponent) and placed in the upper half. Mapping: P0 `global_x = 3 - local_x`, `global_y = 3 - local_y`; P1 `global_x = local_x`, `global_y = local_y + 4`. Every unit enters combat at **full HP** (using level-scaled max HP), regardless of any prior state. All combat logic operates on global combat coordinates.

**Round reset (after combat):** Combat damage and deaths apply only within the round. After Keep damage is applied, each player's **locked formation** is restored on their placement board: **all units return** at their pre-combat local positions with **full HP**. Players then enter the next PREPARATION phase and may rearrange units freely.

### Tick-based simulation

- Combat runs at a **250 ms logical tick** (4 ticks/second)
- Each tick:
  1. If either player has no units → combat ends (both empty → mutual wipe / `DRAW`)
  2. Decrement cooldowns for all alive units
  3. Snapshot unit IDs that are **alive and off cooldown** → **scheduled actors** for this tick
  4. For each unit in ascending unit ID order (including units later killed this tick):
     - Skip if not in the scheduled set
     - **Healer on heal turn** (every 3rd attack): heal lowest-HP ally on the board (no enemy in range required)
     - Else select target (see target selection rules):
       - If target in range: attack
       - Else if unit still alive: move one orthogonal tile along the BFS shortest path
     - Reset cooldown to 4
  5. Remove dead units
- Combat ends when:
  - One player has no surviving units on the merged board (the other wins), OR
  - **Both players have no surviving units** (mutual wipe — **draw**), OR
  - 40 seconds (160 ticks) have elapsed
- **Time limit outcome:** If both players still have units when the tick cap is reached, the player with **higher total surviving HP** wins the round. Tie-break: more surviving units, then Player 0. The loser's Keep takes damage from the winner's survivors using the standard Keep damage formula.
- **Mutual wipe outcome:** If both players have zero surviving units (typically from same-tick kills), `endReason = DRAW` and there is **no round winner** (`winnerPlayerId = -1`). Each player's Keep takes **1** damage. Then apply [Primary win conditions](#primary-win-conditions-checked-in-order-after-keep-damage).

### Target selection

**Range check:** Attack range is Chebyshev distance (max of |Δx| and |Δy|).

**Ranger:** Consider only enemies within Chebyshev range 3 for attack targeting. If none are in range, fall back to all living enemies (same sort) to choose a movement destination.

**Other units** select from all living enemies using type-specific sort order:

| Unit types | Primary | Tie-break 1 | Tie-break 2 |
|------------|---------|-------------|-------------|
| Squire, Shieldbearer, Knight | Manhattan distance | Lowest current HP | Lowest unit ID |
| Mage, Healer (attack target) | Lowest current HP | Manhattan distance | Lowest unit ID |
| Ranger (in-range only) | Lowest current HP | Manhattan distance | Lowest unit ID |

After a target is chosen for movement/pathing: if the unit is a **Healer on a heal turn** (every 3rd attack), heal the lowest-HP ally on the board instead of attacking or moving. Otherwise, if the target is in range, attack; else move one orthogonal tile along the BFS shortest path.

### Movement

- Units move **one orthogonal tile per movement action** (north, south, east, west only — no diagonal steps)
- Each movement action **recomputes the shortest path using BFS** from the unit's current position based on the live board state
- **Primary goal:** shortest path to a **reachable attack position** — any empty tile from which the unit could attack its target (Chebyshev distance ≤ unit range; for range 1 this is the 8 surrounding tiles of the target)
- **Fallback goal:** if no attack position is reachable, move toward the **closest reachable tile** to the target (minimum Manhattan distance among BFS-reachable tiles)
- **Wait:** the unit only stands still when no attack position is reachable **and** it is already at the closest reachable tile to the target (cooldown still resets)
- Occupied tiles are **blocked** and cannot be entered or traversed
- Units **resume moving** automatically once a path to an attack position or a closer tile opens up
- **Tie-breaking:** when multiple shortest paths or equally good goals exist, use fixed deterministic order — BFS neighbor expansion north → south → east → west, then goal selection by path length, Manhattan distance to target, then `y`, then `x`
- Cannot move off the 4 × 8 combat board

### Attack and damage

```
damage = max(1, attacker.attack - defender.armor)
```

where `armor = 1` for Shieldbearer, else `armor = 0`.

- Minimum damage is always 1
- Splash attacks (Mage) hit enemy orthogonal neighbors one tile from the primary target, not from the Mage
- Splash ignores armor
- Healing (Healer) is capped at max HP

### Keep damage

When combat ends, the **loser's Keep** takes damage based on the **winner's surviving units**:

```
keep_damage = 1
            + count(winner's surviving units)
            + floor(total_hp(winner's surviving units) / 10)
```

**Mutual wipe / draw** (both players have zero surviving units): each Keep takes **1** damage; combat ends with `DRAW` and no round winner. Then apply primary win conditions (Keep KO, round 8, or continue).

Example:
- Mutual wipe → **each Keep takes 1**, no round winner; if both Keeps hit 0 → **match draw**
- Winner has 1 unit at 2 HP → damage = 1 + 1 + 0 = 2
- Winner has 3 units, 25 total HP → damage = 1 + 3 + 2 = 6

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
RESOLVING (TX 1 claim + unlocked combat; TX 2 commits resolution)
    ↓
ROUND_RESULT (events, end snapshots, Keep damage already applied in TX 2)
    ├─ TX 3: both Keeps ≤ 0     →  FINISHED (draw)
    ├─ TX 3: exactly one ≤ 0    →  FINISHED (other wins)
    ├─ TX 3: round = 8          →  FINISHED (Keep HP / gold / draw)
    └─ TX 3: else               →  PREPARATION (next round)
    ↓
FINISHED (match over; ratings applied in TX 3)
```

### State fields

The following is a conceptual model, not an API response or a single database row. See the [API contract](api-contract.md) and [database schema](database-schema.md) for exact representations:

- `game_id`: UUID
- `state`: current phase
- `round_number`: 1–8
- `players`: `[player0, player1]` — index matches `playerId` (0 or 1)
- `players[i].keep_hp`: integer ≥ 0 (source of truth on `game_players`; starts at 20). After damage, store **`max(0, oldHp − damage)`** (no negative Keep HP); recorded `keep_damage` still reflects the calculated amount.
- `players[i].gold`: integer ≥ 0 (no cap)
- `players[i].board`: 4×4 placement grid of unit IDs or null (local coordinates)
- `players[i].lane`: 5-slot holding lane of unit IDs or null
- `players[i].units`: map of `unit_id → { type, level, x, y, location }` (authoritative unit instances)
- `players[i].shop`: list of unit definitions (always Level 1 offerings)
- `players[i].plan`: locked copy of commands (hidden from opponent until round resolves)
- `round_deadline`: absolute timestamp (server time)

---

## 9. Determinism and Replay

### What is stored per round

The following pseudocode combines data stored across round, plan, command, event, and snapshot tables. It is not a replay-export endpoint:

```
{
  game_id,
  round_number,
  rules_version,      // e.g., "1.0" for all MVP rounds
  combat_seed,        // set on TX 1 claim; immutable on retry
  locked_plans: {     // combat inputs from round_plans
    0: { board: { "x,y": { id, type, level }, ... }, lane: [ ... ] },
    1: { board: { ... }, lane: [ ... ] }
  },
  player_commands: [
    [ COMMAND, COMMAND, ... ],  // player 0
    [ COMMAND, COMMAND, ... ]   // player 1
  ],
  combat_events: [
    { type: "UNIT_PLACED", unit_id, unit_type, x, y, player_id, level, currentHp, maxHp, tick },
    { type: "UNIT_MOVED", unit_id, x, y, player_id, tick },
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

**Persistence:** TX 2 atomically writes `combat_events`, combat-end snapshots, Keep HP updates, round outcome, and `RESOLVING → ROUND_RESULT`. Locked plans are the combat inputs.

### Determinism guarantee

**Same input → identical output.** If you replay a round with:
- Same rules version
- Same combat seed
- Same placement board state at start of combat (local coordinates per player)
- Same unit definitions
- Same target selection tie-breaks

Then the combat must produce:
- Identical sequence of events
- Identical final combat board state
- Identical Keep damage

This enables:
- Exact replay from stored events
- Bug reproduction
- Rules version migrations (v1 → v2 with validation)
- Balance simulations
- Efficient unit tests

---

## 10. Win Conditions and Tie-Breaking

### Primary win conditions (checked in order after Keep damage)

1. **Both Keeps ≤ 0** → match draw
2. **Exactly one Keep ≤ 0** → other player wins
3. **Round 8 complete** → higher Keep HP wins; if equal, gold tie-breaker; if gold equal → draw
4. **Otherwise** → continue to next round

After Keep damage (including mutual wipe or empty-board outcomes), always apply this order — there is no separate mutual-wipe match exception.

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

- **Both boards empty** (mutual wipe): round `DRAW`; each Keep takes **1** damage
- **Exactly one board empty:** that side loses; the loser takes normal Keep damage from the winner’s survivors (`1 + survivor_count + floor(total_survivor_hp / 10)`)
- Then apply [Primary win conditions](#primary-win-conditions-checked-in-order-after-keep-damage) (Keep KO, round 8, or continue)

### Simultaneous deaths

- At the start of each tick's action phase, the engine records which units are alive and off cooldown
- Those units **all resolve their actions on that tick**, even if an earlier unit in ID order killed them
- Example: Knight (`unit_001`) and Squire (`unit_002`) both scheduled; Knight attacks first and reduces Squire to 0 HP, but Squire still attacks on the same tick because it was scheduled at tick start
- Both `UNIT_DIED` events occur on the same tick; units are removed at end of tick
- This preserves an already-scheduled attack after a same-tick death. Actions still resolve in ID order, so movement and target availability can depend on that order.

### Healer at max HP

- If lowest-HP ally is already at max HP, Healer's 3rd attack still triggers but heal amount is 0; the attack counter still advances toward the next cycle

### Movement when surrounded

- If a unit cannot move toward the target (all adjacent tiles occupied), it does not move but still resets cooldown

### Same-tick action resolution

- Within a tick: decrement all cooldowns first, then snapshot scheduled actors, then process in ascending unit ID order
- Damage applies immediately when each action resolves
- Dead units are removed at **end of tick** (after all scheduled actions)
- Units that died mid-tick still attack if they were scheduled at tick start; they do not move if already dead

---

## 12. Glossary

- **Placement board:** Each player's 4×4 grid for unit placement during planning (local coordinates; any cell)
- **Combat board:** Merged 4×8 grid used only during combat (global coordinates; stacked vertically)
- **Holding lane:** 5-slot off-board storage for purchased but unplaced units; duplicates allowed until merge
- **Keep:** The defending structure with 20 HP (goal is to reduce to 0)
- **Level:** Unit upgrade tier (1–3). Higher levels use HP-biased HP/ATK tables and Healer heal 5/7/10
- **Merge:** Automatic combination of 3 copies (same type + level) into 1 copy of the next level
- **Unit cap:** Maximum number of units allowed on board (3 initially, 5 in round 5+); each instance counts as 1 regardless of level
- **Cooldown:** Countdown timer for when a unit can act again (starts at 4 ticks = 1 second)
- **In range:** Target within Chebyshev distance ≤ unit.range
- **Manhattan distance:** |Δx| + |Δy|
- **Deterministic:** Same input always produces same output
- **Replay:** Re-running stored events to verify combat outcome or inspect unit behavior
- **Idempotency:** Sending the same command twice has the same effect as sending it once

---

## 13. Rules Versions and Migrations

All rounds include a `rules_version` field, currently `1.0`. The current engine does not dispatch historical rulesets. The field provides a foundation for future:

- Safe rules changes without breaking replays
- Version migrations (e.g., "rebalance Mage splash range")
- A/B testing different rule sets
- Backward compatibility when reading old match data

When implementing a new rules version, you must:

1. Update the unit definitions or mechanics
2. Increment `rules_version` string (e.g., "1.0" → "1.1")
3. Write a migration test comparing old vs. new outcomes
4. Store the old rule set in the codebase for replaying historical matches

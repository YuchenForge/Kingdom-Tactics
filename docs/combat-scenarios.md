# Kingdom Tactics — Combat Scenarios for Testing

These 15 scenarios document core mechanics, edge cases, and determinism. They map to the numbered tests in [`CombatEngineTest.java`](../backend/engine/src/test/java/com/kingdom/engine/CombatEngineTest.java). Run them from `backend/` with `mvn -pl engine test`. Each scenario includes:
- **Setup:** Board state before combat
- **Expected behavior:** What should happen and why
- **Validation:** How to verify correctness
- **Rules tested:** Which game mechanics this scenario exercises

---

## Coordinate conventions for scenarios

Unless noted otherwise, all positions in **Setup** use **local placement coordinates** per player.

At combat start, boards merge into a **4 × 8 combat board** (stacked vertically, chess perspective):

- **Player 0 (top):** board is rotated **180°** before merge — row and column order reversed:
  - `global_x = 3 - local_x`
  - `global_y = 3 - local_y` (rows 0–3)
- **Player 1 (bottom):** unchanged:
  - `global_x = local_x`
  - `global_y = local_y + 4` (rows 4–7)

**Example:** P0 Squire at local `(2, 0)` → combat `(1, 3)`; P1 Squire at local `(1, 2)` → combat `(1, 6)` → 3 tiles apart on the merged board.

Combat events and distance checks use **global combat coordinates**.

---

## Important conventions (read before testing)

These apply to **all** scenarios and explain common doc-vs-test pitfalls:

1. **Player IDs:** Use **Player 0** (top board) and **Player 1** (bottom board). Older drafts used "P1/P2" inconsistently.
2. **Full HP at combat start:** `CombatBoard.merge()` resets every unit to **max HP**. Pre-combat `takeDamage()` on placement boards has **no effect**. To test "lowest HP" targeting at the first shot, use enemies with **different max HP** (e.g. Squire 8 vs Shieldbearer 16), or assert targeting **after** combat has already damaged someone.
3. **Action cadence:** Units start with cooldown **4 ticks** (1 second). They act on ticks 3, 7, 11, … — not every tick. Movement and attacks each consume one action and reset cooldown.
4. **Same-tick actions:** At tick start, units alive and off cooldown are **scheduled** for that tick. They all resolve (attacks included) even if killed earlier in ID order on the same tick. See Scenario 1 (mutual wipe) and Scenario 8.
5. **Keep damage:** Normally only the **loser's Keep** takes damage from the **winner's survivors**: `1 + survivor_count + floor(total_survivor_hp / 10)`. **Mutual wipe** (both boards empty after same-tick deaths) → `endReason = DRAW`, `winnerPlayerId = -1`, **each Keep takes 1** (`getKeepDamageForPlayer(0)` and `getKeepDamageForPlayer(1)` both return 1).
6. **Mage / Healer counters:** Every **3rd attack** (not move) triggers splash or heal. The counter does **not** reset; it triggers again at 6, 9, 12, … On the 3rd attack, Mage performs a normal attack **and** splash; Healer heals the lowest-HP ally on the board instead of attacking — **no enemy in range required** for heal turns.
7. **Time limit (160 ticks):** If both sides still have units when time expires, the side with **higher total surviving HP** wins (tie-break: more units, then Player 0). Keep damage applies to the loser's Keep from the winner's survivors.
8. **Movement (BFS):** Each movement action recomputes the shortest orthogonal path via BFS. Units path first toward a reachable **attack position** (empty tile within Chebyshev range of the target); if none exist, they path toward the closest reachable tile to the target. Units wait only when already at that closest reachable tile. Occupied tiles block movement; paths reopen automatically when blockers move or die. Tie-breaking: north → south → east → west neighbor order, then Manhattan distance to target, then `y`, then `x`.

---

## Scenario 1: Basic melee attack (Squire vs Squire)

**Setup:**
- Player 0 board (local): Squire #1 (`unit_001`) at `(2, 0)` → combat `(1, 3)` (HP=8, ATK=2, RNG=1)
- Player 1 board (local): Squire #2 (`unit_002`) at `(1, 2)` → combat `(1, 6)` (HP=8, ATK=2, RNG=1)
- 3 tiles apart on merged board (Chebyshev distance)

**Expected behavior (tick-by-tick):**

*Start:* `unit_001` (P0 Squire) at `(1, 3)` HP 8; `unit_002` (P1 Squire) at `(1, 6)` HP 8. Chebyshev distance **3** (out of melee range). Both units have cooldown **4** — no actions on ticks 0–2.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 `unit_001` | Moves south → `(1, 4)` |
| 1 | 3 | P1 `unit_002` | Moves north → `(1, 5)` — now **adjacent** (Chebyshev 1) |
| 2 | 7 | P0 `unit_001` | Attacks `unit_002` for **2** → P1 Squire HP **6** |
| 2 | 7 | P1 `unit_002` | Attacks `unit_001` for **2** → P0 Squire HP **6** |
| 3 | 11 | P0 `unit_001` | Attacks `unit_002` for **2** → HP **4** |
| 3 | 11 | P1 `unit_002` | Attacks `unit_001` for **2** → HP **4** |
| 4 | 15 | P0 `unit_001` | Attacks `unit_002` for **2** → HP **2** |
| 4 | 15 | P1 `unit_002` | Attacks `unit_001` for **2** → HP **2** |
| 5 | 19 | P0 `unit_001` | Attacks `unit_002` for **2** → HP **0**, `unit_002` dies |
| 5 | 19 | P1 `unit_002` | Still scheduled — attacks `unit_001` for **2** → HP **0**, `unit_001` dies |

*Notes:* Only **one** move round is needed (3 tiles apart → both close 1 tile → adjacent). Attacks begin on tick **7**, not tick 15. Combat ends tick **20** (`DRAW`).

**Expected outcome:**
- **Mutual wipe / draw** — both units dead on the same final tick
- `endReason == DRAW`, `winnerPlayerId == -1`
- **Each Keep takes 1 damage** (`getKeepDamageForPlayer(0) == 1`, `getKeepDamageForPlayer(1) == 1`)

**Validation:**
- First UNIT_MOVED events occur on tick **3**, not tick 0
- ATTACK events occur in pairs on the same tick until the final wipe
- Both `UNIT_DIED` events on the **same** final tick
- `endReason == DRAW` and `winnerPlayerId == -1`
- `getKeepDamageForPlayer(0) == 1` and `getKeepDamageForPlayer(1) == 1`

**Rules tested:**
- Melee range (RNG=1)
- Movement toward opponent (one tile per action)
- Cooldown cadence (4 ticks)
- Basic damage calculation
- Deterministic ID-ordered resolution within a tick
- Keep damage formula

---

## Scenario 2: Shieldbearer armor

**Setup:**
- Player 0 board (local): Knight at `(2, 3)` → combat `(1, 0)` (HP=18, ATK=5, RNG=1)
- Player 1 board (local): Shieldbearer at `(0, 0)` → combat `(0, 4)` (HP=16, ATK=1, RNG=1)
- 4 tiles apart vertically on merged board

**Expected behavior (tick-by-tick):**

*Start:* `unit_001` (P0 Knight) at `(1, 0)` HP 18 ATK 5; `unit_002` (P1 Shieldbearer) at `(0, 4)` HP 16 ATK 1 armor 1. Chebyshev distance **4** (out of range).

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 Knight | Moves south → `(1, 1)` |
| 1 | 3 | P1 Shieldbearer | Moves north → `(0, 3)` |
| 2 | 7 | P0 Knight | Moves south → `(1, 2)` (still out of range) |
| 2 | 7 | P1 Shieldbearer | **In range** after Knight moves — attacks Knight for **1** → Knight HP **17** |
| 3 | 11 | P0 Knight | Attacks Shieldbearer for **4** (5−1) → SB HP **12** |
| 3 | 11 | P1 Shieldbearer | Attacks Knight for **1** → Knight HP **16** |
| 4 | 15 | P0 Knight | Attacks for **4** → SB HP **8** |
| 4 | 15 | P1 Shieldbearer | Attacks for **1** → Knight HP **15** |
| 5 | 19 | P0 Knight | Attacks for **4** → SB HP **4** |
| 5 | 19 | P1 Shieldbearer | Attacks for **1** → Knight HP **14** |
| 6 | 23 | P0 Knight | Attacks for **4** → SB HP **0**, Shieldbearer dies |
| 6 | 23 | P1 Shieldbearer | Still scheduled — attacks Knight for **1** → Knight HP **13** |

*Notes:* Shieldbearer lands an extra hit on tick **7** (Knight moved into melee range but had not attacked yet). Knight survives at **13 HP** (not 14). Combat ends tick **24**.

**Expected outcome:**
- Knight wins (Player 0)
- Knight survives at **13 HP**
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 1 + floor(13/10) = **3**

**Validation:**
- Shieldbearer takes 4 damage per Knight attack (ATK 5 − armor 1)
- Knight takes 1 damage per Shieldbearer attack (5 hits total, including tick 7)
- Minimum damage is always 1
- `winnerPlayerId == 0`, `keepDamage == 3`

---

## Scenario 3: Ranger targeting (lowest HP priority)

**Setup:**
- Player 0 board (local): Ranger (`unit_001`) at `(2, 0)` → combat `(1, 3)` (HP=7, ATK=4, RNG=3)
- Player 1 board (local): Shieldbearer (`unit_002`) at `(0, 2)` → combat `(0, 6)` (HP=16 — **high-HP decoy**)
- Player 1 board (local): Squire (`unit_003`) at `(1, 2)` → combat `(1, 6)` (HP=8 — **lowest max HP**)
- Both enemies within Chebyshev range 3 of the Ranger

**Expected behavior (tick-by-tick):**

*Start:* `unit_001` (P0 Ranger) at `(1, 3)` HP 7 ATK 4 RNG 3; `unit_002` (P1 Shieldbearer) at `(0, 6)` HP 16; `unit_003` (P1 Squire) at `(1, 6)` HP 8. Both enemies within Chebyshev range **3**. Ranger targets lowest HP → **Squire (`unit_003`)**.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 Ranger | Attacks `unit_003` for **4** → Squire HP **4** |
| 1 | 3 | P1 Shieldbearer | Moves north → `(0, 5)` |
| 1 | 3 | P1 Squire | Moves north → `(1, 5)` |
| 2 | 7 | P0 Ranger | Attacks `unit_003` for **4** → HP **0**, Squire dies |
| 2 | 7 | P1 Shieldbearer | Moves north → `(0, 4)` |
| 3 | 11 | P0 Ranger | Re-targets Shieldbearer; attacks for **3** (4−1 armor) → SB HP **13** |
| 3 | 11 | P1 Shieldbearer | **In range** — attacks Ranger for **1** → Ranger HP **6** |
| 4 | 15 | P0 Ranger | Attacks for **3** → SB HP **10** |
| 4 | 15 | P1 Shieldbearer | Attacks for **1** → Ranger HP **5** |
| 5 | 19 | P0 Ranger | Attacks for **3** → SB HP **7** |
| 5 | 19 | P1 Shieldbearer | Attacks for **1** → Ranger HP **4** |
| 6 | 23 | P0 Ranger | Attacks for **3** → SB HP **4** |
| 6 | 23 | P1 Shieldbearer | Attacks for **1** → Ranger HP **3** |
| 7 | 27 | P0 Ranger | Attacks for **3** → SB HP **1** |
| 7 | 27 | P1 Shieldbearer | Attacks for **1** → Ranger HP **2** |
| 8 | 31 | P0 Ranger | Attacks for **3** → SB HP **0**, Shieldbearer dies |
| 8 | 31 | P1 Shieldbearer | Still scheduled — attacks for **1** → Ranger HP **1** |

*Notes:* Ranger stays at `(1, 3)` and never moves. Squire hits are **4** (no armor); Shieldbearer hits are **3** (armor 1). Ranger **does** take damage once Shieldbearer closes to melee. Combat ends tick **32**.

**Expected outcome:**
- Ranger defeats both enemies
- Ranger HP = **1** (took 6 Shieldbearer hits after Squire died)
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 1 + floor(1/10) = **2**

**Validation:**
- Filter ATTACK events where `attackerId == unit_001`; first target is **`unit_003`**
- Ranger deals **4** damage to Squire, **3** damage to Shieldbearer (armor)
- After Squire death, Ranger attacks Shieldbearer; Ranger finishes at **1 HP**
- `winnerPlayerId == 0`, `keepDamage == 2`

**Rules tested:**
- Ranger special: lowest HP targeting (HP → distance → ID)
- Target re-evaluation after unit death
- Range mechanics (RNG=3)
- Multi-unit combat dynamics

---

## Scenario 4: Knight targeting (nearest enemy)

**Setup:**
- Player 0 board (local): Knight (HP=18, ATK=5, RNG=1) at `(3, 3)` → combat `(0, 0)`
- Player 1 board (local): Squire #1 (`unit_002`, HP=8) at `(0, 2)` → combat `(0, 6)` — Manhattan distance **6** from Knight
- Player 1 board (local): Squire #2 (`unit_003`, HP=8) at `(1, 2)` → combat `(1, 6)` — Manhattan distance **7** from Knight
- Both Squires have equal HP; Knight must choose nearest

**Expected behavior (tick-by-tick):**

*Start:* `unit_001` (P0 Knight) at `(0, 0)` HP 18; `unit_002` (P1 Squire) at `(0, 6)` HP 8 — Manhattan **6**; `unit_003` (P1 Squire) at `(1, 6)` HP 8 — Manhattan **7**. Knight targets nearest → **`unit_002`**. All out of melee range.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 Knight | Moves south → `(0, 1)` |
| 1 | 3 | P1 `unit_002` | Moves north → `(0, 5)` |
| 1 | 3 | P1 `unit_003` | Moves north → `(1, 5)` |
| 2 | 7 | P0 Knight | Moves south → `(0, 2)` |
| 2 | 7 | P1 `unit_002` | Moves north → `(0, 4)` |
| 2 | 7 | P1 `unit_003` | Moves north → `(1, 4)` |
| 3 | 11 | P0 Knight | Still out of range — moves south → `(0, 3)` |
| 3 | 11 | P1 `unit_002` | **In range** — attacks Knight for **2** → Knight HP **16** |
| 3 | 11 | P1 `unit_003` | Attacks Knight for **2** → Knight HP **14** |
| 4 | 15 | P0 Knight | **First attack** — hits `unit_002` for **5** → Squire HP **3** |
| 4 | 15 | P1 `unit_002` | Attacks Knight for **2** → Knight HP **12** |
| 4 | 15 | P1 `unit_003` | Attacks Knight for **2** → Knight HP **10** |
| 5 | 19 | P0 Knight | Attacks `unit_002` for **5** → HP **0**, `unit_002` dies |
| 5 | 19 | P1 `unit_002` | Still scheduled — attacks Knight for **2** → Knight HP **8** |
| 5 | 19 | P1 `unit_003` | Attacks Knight for **2** → Knight HP **6** |
| 6 | 23 | P0 Knight | Switches to `unit_003` — attacks for **5** → Squire HP **3** |
| 6 | 23 | P1 `unit_003` | Attacks Knight for **2** → Knight HP **4** |
| 7 | 27 | P0 Knight | Attacks `unit_003` for **5** → HP **0**, `unit_003` dies |
| 7 | 27 | P1 `unit_003` | Still scheduled — attacks Knight for **2** → Knight HP **2** |

*Notes:* Knight paths exclusively toward `unit_002` until it dies; no attacks on `unit_003` until tick 23. Both Squires strike from tick 11 onward (they reach range before Knight does). Combat ends tick **28**.

**Expected outcome:**
- Knight defeats both Squires (Player 0)
- Knight HP = **2**
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 1 + floor(2/10) = **2**

**Validation:**
- Knight's first ATTACK (tick **15**) targets `unit_002`
- No ATTACK on `unit_003` until `unit_002` is dead
- Squires begin attacking Knight on tick **11** (before Knight's first attack)
- Tie-break order for Knight: **distance → HP → ID**
- `winnerPlayerId == 0`, `keepDamageForPlayer(1) == 2`

**Rules tested:**
- Knight special: nearest enemy targeting
- Manhattan distance calculation
- Target priority by distance
- Multi-unit engagement order

---

## Scenario 5: Mage splash damage (every 3rd attack)

**Setup:**
- Player 0 board (local): Mage (`unit_001`, HP=9, ATK=6, RNG=3) at `(3, 0)` → combat `(0, 3)`
- Player 1 board (local): Shieldbearer (`unit_002`, HP=16) at `(1, 0)` → combat `(1, 4)` — diagonally adjacent to Mage (Manhattan **2**)
- Player 1 board (local): Shieldbearer (`unit_003`, HP=16) at `(0, 2)` → combat `(0, 6)` — farther (Manhattan **3**)
- Mage in Chebyshev range 3 of both; no movement needed

**Expected behavior (tick-by-tick):**

*Start:* Mage at `(0, 3)` HP 9. Both Shieldbearers HP 16. Mage targets lowest HP (tied) → closer by Manhattan → **`unit_002`**. Action counter = 0.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 Mage | **Attack 1** — hits `unit_002` for **5** (6−1) → SB1 HP **11** |
| 1 | 3 | P1 `unit_002` | Attacks Mage for **1** → Mage HP **8** |
| 1 | 3 | P1 `unit_003` | Moves north → `(0, 5)` |
| 2 | 7 | P0 Mage | **Attack 2** — hits `unit_002` for **5** → SB1 HP **6** |
| 2 | 7 | P1 `unit_002` | Attacks Mage for **1** → Mage HP **7** |
| 2 | 7 | P1 `unit_003` | Moves north → `(0, 4)` |
| 3 | 11 | P0 Mage | **Attack 3** (counter=3) — hits `unit_002` for **5** → SB1 HP **1**; **splash** hits `unit_003` for **6** (full ATK, no armor) → SB2 HP **10** |
| 3 | 11 | P1 `unit_002` | Attacks Mage for **1** → Mage HP **6** |
| 3 | 11 | P1 `unit_003` | Attacks Mage for **1** → Mage HP **5** |
| 4 | 15 | P0 Mage | **Attack 4** — hits `unit_002` for **5** → HP **0**, `unit_002` dies |
| 4 | 15 | P1 `unit_002` | Still scheduled — attacks Mage for **1** → Mage HP **4** |
| 4 | 15 | P1 `unit_003` | Attacks Mage for **1** → Mage HP **3** |
| 5 | 19 | P0 Mage | **Attack 5** — hits `unit_003` for **5** → SB2 HP **5** |
| 5 | 19 | P1 `unit_003` | Attacks Mage for **1** → Mage HP **2** |
| 6 | 23 | P0 Mage | **Attack 6** (counter=6) — hits `unit_003` for **5** → HP **0**, `unit_003` dies |
| 6 | 23 | P1 `unit_003` | Still scheduled — attacks Mage for **1** → Mage HP **1** |

*Notes:* Mage never moves (in range from tick 3). Direct hits on Shieldbearers deal **5** (armor); splash deals **6** (full ATK, armor ignored) to **orthogonal** neighbors of the primary target. Counter ends at **6**; next splash would be attack 9. Combat ends tick **24**.

**Expected outcome:**
- Mage defeats both Shieldbearers (Player 0)
- Mage HP = **1**; action counter = **6**
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 1 + floor(1/10) = **2**

**Validation:**
- Mage ATTACK count increments only on attacks (0 moves before first attack)
- First attack on tick **3**; attack 3 on tick **11** produces **2** ATTACK events (direct + splash)
- Splash damage = **6** (full ATK, armor ignored); direct damage = **5**
- `unit_002` dies tick **15**; `unit_003` dies tick **23**
- Counter continues past 3 (triggers at 6, 9, …)

**Rules tested:**
- Mage special: splash every 3rd **attack**
- Attack counter mechanic
- Orthogonal neighbor splash targeting
- Splash damage ignores armor

---

## Scenario 6: Healer support (every 3rd attack)

**Setup:**
- Player 0 board (local): Healer (`unit_001`, HP=10, ATK=1, RNG=2) at `(0, 0)` → combat `(3, 3)`
- Player 0 board (local): Squire (`unit_002`, HP=8, ATK=2, RNG=1) at `(2, 0)` → combat `(1, 3)`
- Player 1 board (local): Squire (`unit_003`, HP=8, ATK=2, RNG=1) at `(0, 1)` → combat `(0, 5)`
- Ally takes three hits (down to **2 HP**) before Healer's 3rd attack, so the heal applies the **full 5 HP** (not capped — see Scenario 9 for cap behavior)

**Expected behavior (tick-by-tick):**

*Start:* Healer `(3, 3)` HP 10; Squire `(1, 3)` HP 8; Enemy `(0, 5)` HP 8. Healer out of range initially (Chebyshev **3**); ally also out of range (Chebyshev **2**).

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 Healer | Moves west → `(2, 3)` toward enemy (move — counter stays 0) |
| 1 | 3 | P0 Squire | Moves south → `(1, 4)` toward enemy |
| 1 | 3 | P1 Squire | Attacks `unit_002` for **2** → Ally Squire HP **6** |
| 2 | 7 | P0 Healer | **Attack 1** — hits `unit_003` for **1** → Enemy HP **7** |
| 2 | 7 | P0 Squire | Attacks `unit_003` for **2** → Enemy HP **5** |
| 2 | 7 | P1 Squire | Attacks `unit_002` for **2** → Ally Squire HP **4** |
| 3 | 11 | P0 Healer | **Attack 2** — hits `unit_003` for **1** → Enemy HP **4** |
| 3 | 11 | P0 Squire | Attacks `unit_003` for **2** → Enemy HP **2** |
| 3 | 11 | P1 Squire | Attacks `unit_002` for **2** → Ally Squire HP **2** |
| 4 | 15 | P0 Healer | **Attack 3** — **heals** `unit_002` for **5** (2 → 7) instead of attacking |
| 4 | 15 | P0 Squire | Attacks `unit_003` for **2** → HP **0**, Enemy dies |
| 4 | 15 | P1 Squire | Still scheduled — attacks `unit_002` for **2** → Ally Squire HP **5** |

*Notes:* Healer's tick-3 move delays attacks to ticks 7/11/15, so heal lands on tick **15** when ally is at **2 HP** — full **5** HP restored. Heal is **not** capped here (2 + 5 = 7 < 8 max). Combat ends tick **16**.

**Expected outcome:**
- **Player 0 wins** — Healer and Squire survive
- Healer HP = **10**; Squire HP = **5**
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 2 + floor(15/10) = **4**

**Validation:**
- Exactly **1** `HEALED` event: `healerId == unit_001`, `targetId == unit_002`, `amount == 5`
- Heal occurs on tick **15** (3rd Healer **attack**; the tick-3 action was a move)
- Ally at **2 HP** before heal → receives full **5** HP (not a partial cap)
- Enemy dies tick **15**; `winnerPlayerId == 0`
- `keepDamageForPlayer(1) == 4`

**Rules tested:**
- Healer special: heal every 3rd **attack** (moves do not count)
- Heal targets lowest-HP ally on the board (no range limit)
- Full heal amount (+5 HP when ally has room)
- Ally support enables a win 2v1

---

## Scenario 7: Time limit (40 seconds)

**Setup:**
- Player 0 board (local): Healer (`unit_001`, HP=10, ATK=1, RNG=2) at `(0, 0)` → combat `(3, 3)`
- Player 1 board (local): Healer (`unit_002`, HP=10, ATK=1, RNG=2) at `(3, 3)` → combat `(3, 7)`
- Same column, four rows apart — neither can eliminate the other before **160 ticks** (seed `54321`)

**Expected behavior (tick-by-tick summary):**

*Start:* Both Healers HP 10. Chebyshev distance **4** — out of range; both move on tick 3.

| Phase | Ticks | What happens |
|-------|-------|----------------|
| 1 | 3 | Both move toward each other → P0 `(3, 4)`, P1 `(3, 6)` (pathing toward attack range) |
| 2 | 7, 11 | Both in range — trade **1** damage per hit each tick (HP **9** → **8**) |
| 3 | 15 | Each Healer's **3rd attack** → self-heal **+2** (8 → 10, capped); counter continues |
| 4 | 19–154 | Cycle repeats: attacks every 4 ticks, heal every 3rd attack (+2 when at 8 HP) |
| 5 | 155, 159 | Final attack round + heal round (both back to **10 HP**) |
| 6 | 160 | `COMBAT_ENDED` reason **`TIME_LIMIT`** — both still alive |

*Notes:* Neither side can burst down a Healer before the clock expires. At expiry both have **10 HP** (tied) → tie-break favors **Player 0** (more units tied → P0 wins).

**Expected outcome:**
- `finalTick == 160`, `endReason == TIME_LIMIT`
- **Player 0 wins** on HP tie-break (10 vs 10, equal units → P0)
- Both Healers survive at **10 HP**
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 1 + floor(10/10) = **3**

**Validation:**
- `finalTick == 160` and `endReason == "TIME_LIMIT"`
- No elimination — both players have 1 unit at full HP
- `getTotalHpForPlayer(0) == getTotalHpForPlayer(1) == 10`
- `winnerPlayerId == 0`
- `keepDamageForPlayer(1) == 3`

**Rules tested:**
- Time limit (40 seconds / 160 ticks)
- Time limit outcome (highest total HP wins; tie-break → P0)
- Heal cadence continues through long fights

---

## Scenario 8: Same-tick attacks and kill order

**Setup:**
- Player 0 board (local): Knight (`unit_001`, HP=18, ATK=5, RNG=1) at `(3, 0)` → combat `(0, 3)`
- Player 1 board (local): Squire (`unit_002`, HP=8, ATK=2, RNG=1) at `(0, 0)` → combat `(0, 4)`
- Units start **adjacent** (Chebyshev distance 1) and at **full HP** after merge (seed `111`)

**Expected behavior (tick-by-tick):**

*Start:* Knight `(0, 3)` HP 18; Squire `(0, 4)` HP 8. Both in melee range. Cooldown **4** — no actions ticks 0–2.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 Knight | Attacks `unit_002` for **5** → Squire HP **3** |
| 1 | 3 | P1 Squire | Attacks `unit_001` for **2** → Knight HP **16** |
| 2 | 7 | P0 Knight | Attacks `unit_002` for **5** → HP **0**, Squire dies |
| 2 | 7 | P1 Squire | Still scheduled — attacks Knight for **2** → Knight HP **14** |

*Notes:* Both units scheduled at tick start. Knight attacks first (lower ID). Squire **still attacks** after dying on tick 7. Dead units removed **after** the tick. Combat ends tick **8**.

**Expected outcome:**
- Knight wins (Player 0) at **14 HP** (not 16 — Squire's tick-7 hit still lands)
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 1 + floor(14/10) = **3**

**Validation:**
- First actions on tick **3** (cooldown), not tick 0
- Tick **3** has **2** ATTACK events (both units)
- Tick **7** has **2** ATTACK events — `unit_002` attacks after `UNIT_DIED` on same tick
- `winnerPlayerId == 0`, `keepDamageForPlayer(1) == 3`

**Rules tested:**
- Same-tick attack resolution
- Deterministic ID order within a tick (`unit_001` before `unit_002`)
- Scheduled units act even if killed earlier in the tick
- Unit removal at end of tick
- Merge resets HP to max at combat start

---

## Scenario 9: Healing capped at max HP

**Setup:**
- Player 0 board (local): Healer (`unit_001`, HP=10, ATK=1, RNG=2) at `(0, 0)` → combat `(3, 3)`
- Player 0 board (local): Squire (`unit_002`, HP=8, ATK=2, RNG=1) at `(1, 0)` → combat `(2, 3)`
- Player 1 board (local): Squire (`unit_003`, HP=8, ATK=2, RNG=1) at `(1, 0)` → combat `(1, 4)`
- Healer and ally Squire both in range from tick 3; ally is damaged to **4 HP** before Healer's 3rd attack, so heal is capped at max HP

**Expected behavior (tick-by-tick):**

*Start:* Healer `(3, 3)` HP 10; ally Squire `(2, 3)` HP 8; enemy Squire `(1, 4)` HP 8. Healer action counter = 0. All three can act in range on tick 3.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 Healer | **Attack 1** — hits `unit_003` for **1** → Enemy HP **7** |
| 1 | 3 | P0 Squire | Attacks `unit_003` for **2** → Enemy HP **5** |
| 1 | 3 | P1 Squire | Attacks `unit_002` for **2** → Ally Squire HP **6** |
| 2 | 7 | P0 Healer | **Attack 2** — hits `unit_003` for **1** → Enemy HP **4** |
| 2 | 7 | P0 Squire | Attacks `unit_003` for **2** → Enemy HP **2** |
| 2 | 7 | P1 Squire | Attacks `unit_002` for **2** → Ally Squire HP **4** |
| 3 | 11 | P0 Healer | **Attack 3** — **heals** `unit_002` for **4** (4 → 8, capped at max HP) instead of attacking |
| 3 | 11 | P0 Squire | Attacks `unit_003` for **2** → HP **0**, enemy dies |
| 3 | 11 | P1 Squire | Still scheduled — attacks `unit_002` for **2** → Ally Squire HP **6** |

*Notes:* Heal fires on the **3rd attack** (tick 11), restoring ally from **4 HP** to **8 HP**. Since Squire max HP is 8, the heal amount is **4**, not 5. Combat ends tick **12**.

**Expected outcome:**
- Healer + Squire win (Player 0)
- Healer HP = **10**; Squire HP = **6**
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 2 + floor(16/10) = **4**

**Validation:**
- `HEALED` event on tick **11**: `healerId == unit_001`, `targetId == unit_002`, `amount == 4` (capped, not 5)
- Ally Squire HP before heal = **4**; after heal = **8**; never exceeds max
- No Healer moves — action counter reaches 3 purely from attacks
- `winnerPlayerId == 0`, `keepDamageForPlayer(1) == 4`

**Rules tested:**
- Healing capped at max HP (`min(current + 5, max)`)
- Heal targets lowest-HP ally
- Heal turn replaces attack (3rd attack)
- Heal cycle continues at attacks 6, 9, 12, …

---

## Scenario 10: Empty board (one player places no units)

**Setup:**
- Player 0 board: **empty** (0 units)
- Player 1 board (local): Squire (`unit_001`, HP=8) at `(0, 0)` → combat `(0, 4)`
- Seed: `12345`

**Expected behavior (tick-by-tick):**

*Start:* Player 0 has no units after merge. Player 1 Squire at `(0, 4)` HP 8.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 0 | — | `COMBAT_ENDED` reason **`ENEMY_VICTORY`** — no actions resolve |

*Notes:* Empty-board check runs before the first action tick. No movement, no attacks, no cooldown phase.

**Expected outcome:**
- Player 1 wins at tick **0**
- `endReason == ENEMY_VICTORY`
- Squire survives at **8 HP** on `(0, 4)`
- Player 0 Keep damage: 1 + 1 + floor(8/10) = **2**
- Player 1 Keep damage: **0**

**Validation:**
- `finalTick == 0`, `winnerPlayerId == 1`
- Zero `ATTACK` events in the log
- `getKeepDamageForPlayer(0) == 2`, `getKeepDamageForPlayer(1) == 0`
- `getFinalBoard().getUnit("unit_001").getCurrentHp() == 8`

**Rules tested:**
- Empty board condition
- Immediate combat resolution (tick 0)
- Keep damage for winner with surviving units

---

## Scenario 11: Tie-breaking by ID (multiple units at same distance and HP)

**Setup:**
- Player 0 board (local): Knight (`unit_001`, HP=18, ATK=5, RNG=1) at `(2, 3)` → combat `(1, 0)`
- Player 1 board (local): Squire #1 (`unit_002`, HP=8) at `(0, 2)` → combat `(0, 6)` — Manhattan **7** from Knight
- Player 1 board (local): Squire #2 (`unit_003`, HP=8) at `(2, 2)` → combat `(2, 6)` — Manhattan **7** from Knight
- Both Squires equidistant and same HP at start; ID tie-break applies when Knight first attacks (seed `54321`)

**Expected behavior (tick-by-tick):**

*Start:* Knight `(1, 0)` HP 18; Squires `(0, 6)` and `(2, 6)` HP 8. All out of melee range.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 Knight | Moves south → `(1, 1)` |
| 1 | 3 | P1 `unit_002` | Moves north → `(0, 5)` |
| 1 | 3 | P1 `unit_003` | Moves north → `(2, 5)` |
| 2 | 7 | P0 Knight | Moves south → `(1, 2)` |
| 2 | 7 | P1 `unit_002` | Moves north → `(0, 4)` |
| 2 | 7 | P1 `unit_003` | Moves north → `(2, 4)` |
| 3 | 11 | P0 Knight | Moves south → `(1, 3)` — still out of melee range |
| 3 | 11 | P1 `unit_002` | Attacks Knight for **2** → Knight HP **16** |
| 3 | 11 | P1 `unit_003` | Attacks Knight for **2** → Knight HP **14** |
| 4 | 15 | P0 Knight | **First attack** — Manhattan tie (2 vs 2), HP tie (8 vs 8) → ID tie-break → hits **`unit_002`** for **5** → Squire HP **3** |
| 4 | 15 | P1 `unit_002` | Attacks Knight for **2** → Knight HP **12** |
| 4 | 15 | P1 `unit_003` | Attacks Knight for **2** → Knight HP **10** |
| 5 | 19 | P0 Knight | Attacks `unit_002` for **5** → HP **0**, `unit_002` dies |
| 5 | 19 | P1 `unit_002` | Still scheduled — attacks Knight for **2** → Knight HP **8** |
| 5 | 19 | P1 `unit_003` | Attacks Knight for **2** → Knight HP **6** |
| 6 | 23 | P0 Knight | Switches to `unit_003` — attacks for **5** → Squire HP **3** |
| 6 | 23 | P1 `unit_003` | Attacks Knight for **2** → Knight HP **4** |
| 7 | 27 | P0 Knight | Attacks `unit_003` for **5** → HP **0**, `unit_003` dies |
| 7 | 27 | P1 `unit_003` | Still scheduled — attacks Knight for **2** → Knight HP **2** |

*Notes:* Initial Manhattan distance is **7** (not 3). ID tie-break matters at tick **15** when both Squires are at Manhattan **2** with **8 HP**. Knight never attacks `unit_003` until `unit_002` is dead. Combat ends tick **28**.

**Expected outcome:**
- Knight wins (Player 0) at **2 HP**
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 1 + floor(2/10) = **2**

**Validation:**
- Knight's first ATTACK (tick **15**) targets `unit_002`
- No ATTACK on `unit_003` until `unit_002` dies (tick 23)
- Squires begin attacking Knight on tick **11** (before Knight's first attack)
- Tie-break order for Knight: **distance → HP → ID**
- `winnerPlayerId == 0`, `keepDamageForPlayer(1) == 2`

**Rules tested:**
- Tie-breaking by unit ID
- Deterministic targeting with identical units
- Multi-unit priority ordering

---

## Scenario 12: Pre-combat damage not preserved through merge

**Setup:**
- Player 0 board (local): Squire (`unit_001`, HP=8 max) at `(2, 0)` → combat `(1, 3)`
- Player 1 board (local): Squire (`unit_002`, HP=8) at `(1, 2)` → combat `(1, 6)`
- **Before merge:** call `takeDamage(5)` on P0's Squire on the placement board → **3 HP** locally
- Same board layout as Scenario 1; seed `12345`

*Why this setup?* If pre-combat damage carried into combat, P0's Squire would die much earlier (first hit leaves 1 HP, second hit kills on tick **11**). The actual trace matches Scenario 1's mutual wipe on tick **19**, proving merge resets HP to max.

**Expected behavior (tick-by-tick):**

*Placement board:* `unit_001` has **3 HP** before `CombatEngine.resolve()`.

*Combat start (after merge):* Both Squires reset to **8 HP** at `(1, 3)` and `(1, 6)`. Chebyshev distance **3** — out of melee range.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 `unit_001` | Moves south → `(1, 4)` |
| 1 | 3 | P1 `unit_002` | Moves north → `(1, 5)` — now adjacent |
| 2 | 7 | P0 `unit_001` | Attacks `unit_002` for **2** → P1 Squire HP **6** |
| 2 | 7 | P1 `unit_002` | Attacks `unit_001` for **2** → P0 Squire HP **6** |
| 3 | 11 | P0 `unit_001` | Attacks for **2** → HP **4** |
| 3 | 11 | P1 `unit_002` | Attacks for **2** → HP **4** |
| 4 | 15 | P0 `unit_001` | Attacks for **2** → HP **2** |
| 4 | 15 | P1 `unit_002` | Attacks for **2** → HP **2** |
| 5 | 19 | P0 `unit_001` | Attacks for **2** → HP **0**, `unit_002` dies |
| 5 | 19 | P1 `unit_002` | Still scheduled — attacks for **2** → HP **0**, `unit_001` dies |

*Notes:* Combat is **identical to Scenario 1** — four full attack rounds before mutual wipe. If the placement-board 3 HP carried over, P0 would be dead by tick **11**, not tick **19**. Combat ends tick **20** (`DRAW`).

**Expected outcome:**
- **Mutual wipe / draw** — same as Scenario 1
- `endReason == DRAW`, `winnerPlayerId == -1`
- Each Keep takes **1** damage

**Validation:**
- Placement-board `unit_001.getCurrentHp() == 3` **before** resolve
- First ATTACK pair on tick **7** shows both Squires at **6 HP** after (started at 8, not 3)
- Both `UNIT_DIED` on tick **19** — not tick **11**
- `finalTick == 20`, `endReason == "DRAW"`
- `getKeepDamageForPlayer(0) == 1`, `getKeepDamageForPlayer(1) == 1`

**Rules tested:**
- `CombatBoard.merge()` resets every unit to max HP
- Pre-combat `takeDamage()` on placement boards has no combat effect
- Merge creates fresh `UnitInstance` copies via `copyForCombat()`

---

## Scenario 13: Ranger vs multiple targets (distance tie-break)

**Setup:**
- Player 0 board (local): Ranger (`unit_001`, HP=7, ATK=4, RNG=3) at `(2, 0)` → combat `(1, 3)`
- Player 1 board (local): Squire #1 (`unit_002`, HP=8) at `(1, 1)` → combat `(1, 5)` — Manhattan **2** from Ranger
- Player 1 board (local): Squire #2 (`unit_003`, HP=8) at `(2, 2)` → combat `(2, 6)` — Manhattan **4** from Ranger
- Both Squires have equal HP; Ranger uses HP → distance → ID (seed `54321`)

**Expected behavior (tick-by-tick):**

*Start:* Ranger `(1, 3)` HP 7; Squire #1 `(1, 5)` HP 8; Squire #2 `(2, 6)` HP 8. Both are in Ranger range (Chebyshev ≤ 3), so distance breaks the HP tie.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 Ranger | Attacks closer `unit_002` for **4** → Squire #1 HP **4** |
| 1 | 3 | P1 `unit_002` | Moves north → `(1, 4)` |
| 1 | 3 | P1 `unit_003` | Moves north → `(2, 5)` |
| 2 | 7 | P0 Ranger | Attacks `unit_002` for **4** → HP **0**, Squire #1 dies |
| 2 | 7 | P1 `unit_002` | Still scheduled — attacks Ranger for **2** → Ranger HP **5** |
| 2 | 7 | P1 `unit_003` | Moves north → `(2, 4)` |
| 3 | 11 | P0 Ranger | Switches to `unit_003` — attacks for **4** → Squire #2 HP **4** |
| 3 | 11 | P1 `unit_003` | Attacks Ranger for **2** → Ranger HP **3** |
| 4 | 15 | P0 Ranger | Attacks `unit_003` for **4** → HP **0**, Squire #2 dies |
| 4 | 15 | P1 `unit_003` | Still scheduled — attacks Ranger for **2** → Ranger HP **1** |

*Notes:* Ranger never moves because both enemies are in range from tick 3. Same-tick death rules let each Squire attack on the tick it dies. Combat ends tick **16**.

**Expected outcome:**
- Ranger defeats closer Squire first, then Squire #2
- Ranger HP = **1**
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 1 + floor(1/10) = **2**

**Validation:**
- Ranger's first ATTACK (tick **3**) targets closer `unit_002`
- No Ranger ATTACK on `unit_003` until `unit_002` is dead
- `unit_002` dies tick **7**; `unit_003` dies tick **15**
- Tie-break order for Ranger: **HP → distance → ID**
- `winnerPlayerId == 0`, `keepDamageForPlayer(1) == 2`

**Rules tested:**
- Ranger targeting with distance tie-breaks
- Lowest HP, then distance, then ID ordering
- Same-tick scheduled attacks after death

---

## Scenario 14: Healer healing self (when lowest HP)

**Setup:**
- Player 0 board (local): Healer (`unit_001`, HP=10, ATK=1, RNG=2) at `(0, 0)` → combat `(3, 3)`
- Player 0 board (local): Knight (`unit_003`, HP=18, ATK=5, RNG=1) at `(1, 1)` → combat `(2, 2)`
- Player 1 board (local): Squire (`unit_002`, HP=8, ATK=2, RNG=1) at `(2, 0)` → combat `(2, 4)`
- Squire reaches and damages the Healer **during combat** (not pre-combat — merge resets HP), making the Healer the lowest-HP ally before its 3rd attack

**Expected behavior (tick-by-tick):**

*Start:* Healer `(3, 3)` HP 10; Knight `(2, 2)` HP 18; Squire `(2, 4)` HP 8. Healer is in range; Knight is out of melee range.

| Step | Tick | Actor | Action |
|------|------|-------|--------|
| 1 | 3 | P0 Healer | **Attack 1** — hits `unit_002` for **1** → Squire HP **7** |
| 1 | 3 | P1 Squire | Attacks closer/lower-HP `unit_001` for **2** → Healer HP **8** |
| 1 | 3 | P0 Knight | Moves south → `(2, 3)` |
| 2 | 7 | P0 Healer | **Attack 2** — hits `unit_002` for **1** → Squire HP **6** |
| 2 | 7 | P1 Squire | Now nearest to Knight — attacks `unit_003` for **2** → Knight HP **16** |
| 2 | 7 | P0 Knight | Attacks `unit_002` for **5** → Squire HP **1** |
| 3 | 11 | P0 Healer | **Attack 3** — **self-heals** `unit_001` for **2** (8 → 10) instead of attacking |
| 3 | 11 | P1 Squire | Attacks `unit_003` for **2** → Knight HP **14** |
| 3 | 11 | P0 Knight | Attacks `unit_002` for **5** → HP **0**, Squire dies |

*Notes:* Healer's 3rd attack is a heal turn. Since Healer is the lowest-HP ally at **8 HP**, `findLowestHpAlly` selects the Healer itself and caps the heal at max HP. Combat ends tick **12**.

**Expected outcome:**
- Healer self-heals for **2** and survives at **10 HP**
- Knight survives at **14 HP**
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 2 + floor(24/10) = **5**

**Validation:**
- One `HEALED` event on tick **11** where `healerId == targetId == unit_001`, `amount == 2`
- Self-heal works when Healer is lowest HP ally on the board
- Heal turn replaces attack (no Healer damage on tick 11)
- `winnerPlayerId == 0`, `keepDamageForPlayer(1) == 5`

**Rules tested:**
- Healer self-healing
- Ally targeting includes self
- Healing capped at max HP
- Heal replaces every 3rd attack

---

## Scenario 15: Deterministic replay (same seed, same outcome)

**Setup:**
- Run **Scenario 3** twice with identical boards, unit IDs, and seed `54321`:
  - Player 0 Ranger (`unit_001`) at local `(2, 0)` → combat `(1, 3)`
  - Player 1 Shieldbearer (`unit_002`) at local `(0, 2)` → combat `(0, 6)`
  - Player 1 Squire (`unit_003`) at local `(1, 2)` → combat `(1, 6)`

**Expected behavior (tick-by-tick summary):**

| Phase | Ticks | What happens |
|-------|-------|--------------|
| 1 | 3, 7 | Ranger targets lowest-HP `unit_003` first and kills it on tick **7** |
| 2 | 11–31 | Ranger switches to Shieldbearer; direct hits deal **3** each (4−1 armor) |
| 3 | 31 | Shieldbearer dies; still scheduled and attacks Ranger after death |
| 4 | 32 | `COMBAT_ENDED` reason **`PLAYER_VICTORY`** |

*Notes:* Both replays produce exactly **23 events** (including placement events), with identical event types, ticks, unit IDs, damage values, and ordering.

**Expected outcome:**
- Both replays match exactly
- Player 0 wins at tick **32**
- `endReason == PLAYER_VICTORY`
- Player 0 Keep damage: **0**
- Player 1 Keep damage: 1 + 1 + floor(1/10) = **2**

**Validation:**
- Compare full event lists with `.equals()` or size + per-field assertions
- Same `winnerPlayerId`, `keepDamage`, `endReason`, `finalTick`
- Event count is **23**
- No randomness in targeting, movement, or damage (RNG seeded but unused in current MVP)

**Rules tested:**
- Deterministic combat
- Seeded RNG consistency
- Replay fidelity
- Rules version immutability

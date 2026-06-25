# ADR 003: Deterministic Combat Engine

**Date:** 2024
**Status:** Accepted
**Affects:** Engine design, gameplay experience, testing strategy

---

## Context

Kingdom Tactics is a tactical auto-battler where the outcome is determined server-side. To ensure fairness and enable replay, the combat engine must produce identical results given identical inputs.

**Non-deterministic approaches** (considered and rejected):
1. Client-side random number generation (cheating risk)
2. Server-side RNG without seeding (non-reproducible)
3. Physics simulation with floating-point math (rounding errors)

**Deterministic approach** (chosen):
- Use seeded RNG and fixed-point math
- All tie-breaks resolved by stable unit IDs
- No floating-point arithmetic in combat

---

## Decision

**Implement a seeded, deterministic combat engine with reproducible tick-by-tick simulation.**

### Guarantees

Given:
- `rules_version` (immutable set of unit definitions and mechanics)
- `combat_seed` (unique, random 64-bit seed per round)
- `board_state_start` (unit placements, HP, positions)
- `player_commands` (buy, sell, place, lock)

The engine **must always produce**:
- Identical sequence of combat events
- Identical final board state
- Identical Keep damage calculation
- Within margin of 0 ticks (bit-for-bit identical)

### Implementation requirements

1. **Seeded RNG:** All randomness uses a seeded PRNG (e.g., `java.util.Random` with seed)
2. **Deterministic tick loop:** Each tick processes units in same order
3. **Fixed-point math:** No floating-point; use integers (damage is always int)
4. **Stable tie-breaking:** Ties always resolve by unit ID (stable, immutable)
5. **No external state:** Engine does not depend on system time, disk, network
6. **Pure functions:** Same input → same output (functional purity)

---

## Implementation

### Seeded RNG usage

```java
public class DeterministicCombat {
  private final Random rng;  // seeded in constructor
  
  DeterministicCombat(long seed) {
    this.rng = new Random(seed);
  }
  
  void resolve(Board board, List<Command>[] playerCommands) {
    for (int tick = 0; tick < MAX_TICKS; tick++) {
      // Deterministic order: sort units by ID
      List<UnitInstance> units = sortById(board.allUnits());
      
      for (UnitInstance unit : units) {
        if (!unit.isAlive()) continue;
        
        unit.decrementCooldown();
        if (unit.canAct()) {
          UnitInstance target = selectTarget(unit, board, rng);
          if (target != null) {
            attack(unit, target);
          } else {
            moveTowardNearestEnemy(unit, board);
          }
        }
      }
      
      // All damage applied; remove dead units
      board.removeDeadUnits();
    }
  }
  
  private UnitInstance selectTarget(UnitInstance unit, Board board, Random rng) {
    List<UnitInstance> enemiesInRange = board.getEnemiesInRange(unit);
    if (enemiesInRange.isEmpty()) return null;
    
    // Apply tie-breaking rules specific to unit type
    return unit.selectTarget(enemiesInRange, rng);
  }
}
```

### Target selection with seeding

```java
public class Ranger extends UnitDefinition {
  @Override
  UnitInstance selectTarget(List<UnitInstance> enemies, Random rng) {
    // Lowest HP is primary criterion (no randomness needed)
    int minHp = enemies.stream().mapToInt(u -> u.currentHp).min().orElse(0);
    List<UnitInstance> lowestHpUnits = enemies.stream()
      .filter(u -> u.currentHp == minHp)
      .collect(toList());
    
    if (lowestHpUnits.size() == 1) return lowestHpUnits.get(0);
    
    // Tie-break: sort by unit ID (stable)
    return lowestHpUnits.stream()
      .min(Comparator.comparing(u -> u.id))
      .orElse(null);
  }
}
```

### Tick-by-tick determinism

Order of operations (strictly consistent every tick):

```
Tick T:
  1. Decrement cooldowns (all units, in ID order)
  2. For each unit (ID order):
     a. If alive and cooldown = 0:
        - Select target (via tiebreaks, no randomness in tiebreak)
        - If target in range: attack (damage computed deterministically)
        - Else: move 1 tile toward nearest enemy (Manhattan distance, ID tiebreak)
  3. Apply all damage from this tick
  4. Remove dead units
  5. Record events
Tick T+1: ...
```

### No floating-point math

All calculations use integers:

```java
// Damage calculation (integer only)
int damage = Math.max(1, attacker.attack - defender.armor);

// Keep damage calculation (integer division)
int keepDamage = 1 
  + survivors.size() 
  + (totalSurvivingHp / 10);  // integer division, no floats

// Healing (capped at max, integer)
int healed = Math.min(maxHp - currentHp, HEAL_AMOUNT);
newHp = currentHp + healed;
```

### Unit ID stability

Unit IDs are **chronologically assigned** and **immutable**:

```
Unit 1: Squire placed at round 1 → ID = unit_0001
Unit 2: Knight bought and placed at round 2 → ID = unit_0002
Unit 3: Squire II placed at round 3 → ID = unit_0003
```

All tiebreaks use these IDs in ascending order.

---

## Consequences

### Positive

1. **Perfect replay:** Same seed + state → bit-for-bit identical outcome
2. **Auditability:** Every action can be traced and verified
3. **Testability:** Unit tests are deterministic; no flaky tests
4. **Verifiability:** Can run combat offline and compare to stored result
5. **Fairness:** No hidden randomness; players see consistent behavior
6. **Debugging:** Reproduce bugs exactly by using stored seed and state

### Negative

1. **Gameplay perception:** Outcomes might seem "scripted" if players notice patterns
   - **Mitigation:** Combat is still strategic (placement + unit choice matter); seed is hidden from players
   
2. **RNG-less feels less dynamic:** No critical hits, no dodge chance for variety
   - **Mitigation:** Use seeded RNG for future expansions (traits, synergies) if desired
   
3. **Engine complexity:** Must carefully manage state and ordering
   - **Mitigation:** Pure function testing; no side effects; clear tick loop
   
4. **Limited AI variety:** Opponent behavior is deterministic given board state
   - **Mitigation:** Future bots can use different seed per bot instance

---

## Alternatives considered

### 1. Client-side RNG (rejected)

**Approach:**
- Client generates random events and sends to server
- Server accepts client's RNG results

**Why rejected:**
- Client could cheat by reporting favorable RNG (critical hit on demand)
- No fairness guarantee
- Conflicts with server-authoritative design

### 2. Server-side unseed RNG (rejected)

**Approach:**
- Server uses `java.util.Random()` without seed (uses system time)
- Different result each time

**Why rejected:**
- Cannot replay (seed is lost)
- No auditability
- Cannot verify fairness
- Violates ADR 002 (event log without determinism is useless)

### 3. Client-side RNG + server verification (rejected)

**Approach:**
- Client computes RNG and sends to server with seed
- Server verifies seed is valid

**Why rejected:**
- Client still generates the seed (can pick favorable one)
- Doesn't prevent cheating
- Adds complexity without benefit

### 4. True randomness with commit-reveal (complex, rejected)

**Approach:**
- Both players commit to seed hash before round
- Reveal seeds after combat to compute true random number
- Players cannot cheat without breaking hash commitment

**Why rejected:**
- Adds latency (commit phase + reveal phase)
- Players could abort if reveal is unfavorable
- Overkill for a casual game
- Simpler solution: server chooses seed, players trust server

---

## Related decisions

- **ADR 001:** Server-authoritative state (server computes combat, not client)
- **ADR 002:** Event log and snapshots (enables verification via replay)

---

## Testing strategy

### Unit tests (always deterministic)

```java
@Test
void ranger_targets_lowest_hp_unit() {
  Board board = new Board();
  Ranger ranger = new Ranger(/*...*/, id=1);
  Squire squire1 = new Squire(/*...*/, hp=8, id=2);
  Squire squire2 = new Squire(/*...*/, hp=6, id=3);
  
  board.place(ranger, 0, 0);
  board.place(squire1, 1, 0);  // in range
  board.place(squire2, 1, 1);  // in range
  
  UnitInstance target = ranger.selectTarget(board.getEnemies(), new Random(42));
  assertThat(target).isEqualTo(squire2);  // HP=6 < HP=8
}

@Test
void combat_is_reproducible() {
  long seed = 12345L;
  Board board1 = setupBoard();
  Board board2 = setupBoard();  // identical
  
  List<CombatEvent> events1 = simulate(board1, seed);
  List<CombatEvent> events2 = simulate(board2, seed);
  
  assertThat(events2).containsExactlyElementsOf(events1);
  assertThat(board1.finalState()).isEqualTo(board2.finalState());
}
```

### Integration tests (with stored snapshots)

```java
@Test
void replayed_match_matches_stored_snapshot() {
  // Load historical match from database
  Round round = database.getRound(gameId, roundNumber=3);
  
  // Replay combat
  CombatEngine engine = new CombatEngine(round.rulesVersion, round.combatSeed);
  ResolutionResult result = engine.resolve(
    round.startSnapshot.board,
    round.playerCommands
  );
  
  // Verify against stored snapshot
  assertThat(result.boardState).isEqualTo(round.endSnapshot.board);
  assertThat(result.keepDamage).isEqualTo(round.endSnapshot.keepDamage);
}
```

---

## Questions and answers

**Q: What if a player exploits the determinism by knowing the seed?**
A: Seed is chosen uniformly at random by the server and kept secret from both players. Players cannot predict future combat. Seed is revealed only after combat (for replay), not before.

**Q: Does determinism make the game "boring"?**
A: No. Determinism applies to combat simulation, not to player strategy. Unit placement, synergies, and economic decisions remain strategic and varied. Same placement ≠ same outcome if opponents choose different units.

**Q: Can we add randomness later (e.g., critical hits)?**
A: Yes, using seeded RNG. A new `critChance` property would use the seeded random stream. Old matches remain deterministic under old rules; new matches use updated engine. Backward compatible via rules_version.

**Q: What if two units have the same ID?**
A: Unit IDs are chronologically unique. Impossible by design.

**Q: How do we handle tie-break edge cases (e.g., units placed simultaneously)?**
A: Placement happens in API request order. Server assigns IDs sequentially. No simultaneous placement in reality; the appearance of simultaneity is sequenced by server receipt time.

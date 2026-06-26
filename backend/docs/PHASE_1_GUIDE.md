# Phase 1: Combat Engine - Implementation Guide

## Overview

Phase 1 goal: Build a pure, deterministic, testable combat engine in Java with **zero framework dependencies**.

**Deliverables:**
- ✅ Domain models (UnitDefinition, UnitInstance, Board, CombatEvent, ResolutionResult)
- ⏳ Combat engine with tick loop and target selection
- ⏳ All 6 unit special abilities
- ⏳ 30+ unit tests (one per combat scenario + edge cases)
- ⏳ CLI harness for testing

---

## Current Status

### ✅ Completed

- [x] Maven project structure
- [x] pom.xml with JUnit 5 + AssertJ
- [x] UnitDefinition (6 unit types)
- [x] UnitInstance (position, HP, cooldown, action counter)
- [x] Board (4x4 grid, unit queries)
- [x] CombatEvent (immutable event log)
- [x] ResolutionResult (combat output)
- [x] CombatEngine skeleton
- [x] 3 basic tests

### ⏳ TODO

1. **Complete CombatEngine**
   - [x] Refactor to pass both boards to methods (not just one board)
   - [x] Implement splash damage (Mage)
   - [x] Implement healing (Healer)
   - [x] Fix movement and pathfinding
   - [x] Handle Healer ally healing
   - [x] Keep damage calculation per player

2. **Add TargetSelector service**
   - [x] Ranger: lowest HP targeting
   - [x] Knight: nearest enemy targeting
   - [x] Mage/Healer: lowest HP ally targeting
   - [x] Tie-breaking by distance, HP, unit ID

3. **Add comprehensive tests** (30+)
   - [ ] Scenario 1-15 from docs/combat-scenarios.md
   - [ ] Edge cases: simultaneous death, empty board, etc.
   - [ ] Determinism verification
   - [ ] All unit special abilities

4. **CLI harness**
   - [ ] Read board state from JSON
   - [ ] Run combat
   - [ ] Output events and result as JSON

---

## Recommended Implementation Order

### 1. Fix CombatEngine architecture

The engine needs access to both boards to determine enemies. Refactor:

```java
public ResolutionResult resolve(Board[] boards) {
    Board playerBoard = boards[0];
    Board enemyBoard = boards[1];
    // ... rest of implementation
}
```

### 2. Create TargetSelector service

```java
package com.kingdom.engine.service;

public class TargetSelector {
    /**
     * Select a target for a unit.
     * Considers unit type, range, and tie-breaking rules.
     */
    public static UnitInstance selectTarget(
        UnitInstance unit, 
        Board ownBoard,
        Board enemyBoard) {
        
        List<UnitInstance> inRange = getEnemiesInRange(unit, enemyBoard);
        if (inRange.isEmpty()) return null;
        
        // Sort by unit-specific criteria
        if ("Ranger".equals(unit.getType())) {
            // Lowest HP first
            inRange.sort(Comparator
                .comparingInt(UnitInstance::getCurrentHp)
                .thenComparingInt(e -> unit.manhattanDistance(e))
                .thenComparing(UnitInstance::getId));
        } else if ("Knight".equals(unit.getType())) {
            // Nearest by Manhattan distance
            inRange.sort(Comparator
                .comparingInt(e -> unit.manhattanDistance(e))
                .thenComparingInt(UnitInstance::getCurrentHp)
                .thenComparing(UnitInstance::getId));
        }
        // ... other unit types
        
        return inRange.get(0);
    }
}
```

### 3. Implement special abilities

#### Mage splash (every 3rd action)

```java
if ("Mage".equals(attacker.getType()) && attacker.isTriggerSpecialAction()) {
    // Splash damage to orthogonal neighbors
    int[][] directions = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    for (int[] dir : directions) {
        int neighborX = attacker.getX() + dir[0];
        int neighborY = attacker.getY() + dir[1];
        
        UnitInstance neighbor = enemyBoard.getUnitAt(neighborX, neighborY);
        if (neighbor != null && !neighbor.equals(target)) {
            neighbor.takeDamage(attacker.getAttack());  // No armor reduction
            events.add(CombatEvent.attack(tick, attacker.getId(), neighbor.getId(), attacker.getAttack()));
            if (!neighbor.isAlive()) {
                events.add(CombatEvent.unitDied(tick, neighbor.getId()));
            }
        }
    }
}
```

#### Healer heal (every 3rd action)

```java
if ("Healer".equals(unit.getType()) && unit.isTriggerSpecialAction()) {
    // Find lowest HP ally in range 2
    List<UnitInstance> alliesInRange = ownBoard.getAliveUnits().stream()
        .filter(ally -> unit.chebyshevDistance(ally) <= 2)
        .sorted(Comparator
            .comparingInt(UnitInstance::getCurrentHp)
            .thenComparing(UnitInstance::getId))
        .collect(Collectors.toList());
    
    if (!alliesInRange.isEmpty()) {
        UnitInstance allyToHeal = alliesInRange.get(0);
        int oldHp = allyToHeal.getCurrentHp();
        allyToHeal.heal(5);
        int healed = allyToHeal.getCurrentHp() - oldHp;
        events.add(CombatEvent.healed(tick, unit.getId(), allyToHeal.getId(), healed));
    }
}
```

### 4. Write comprehensive tests

For each scenario from `docs/combat-scenarios.md`, create a test:

```java
@Test
void scenario_1_squire_vs_squire() { ... }

@Test
void scenario_2_knight_vs_shieldbearer() { ... }

@Test
void scenario_3_ranger_targets_lowest_hp() { ... }

@Test
void scenario_4_knight_targets_nearest() { ... }

@Test
void scenario_5_mage_splash_damage() { ... }

@Test
void scenario_6_healer_support() { ... }

// ... 15 scenarios total + edge cases
```

### 5. Create CLI harness

```java
package com.kingdom.engine;

import com.google.gson.Gson;
import com.kingdom.engine.domain.*;
import com.kingdom.engine.service.CombatEngine;

public class CombatCliRunner {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java CombatCliRunner <board-json>");
            System.exit(1);
        }
        
        String boardJson = new String(Files.readAllBytes(Paths.get(args[0])));
        BoardState boardState = new Gson().fromJson(boardJson, BoardState.class);
        
        CombatEngine engine = new CombatEngine(boardState.seed);
        ResolutionResult result = engine.resolve(boardState.playerBoard, boardState.enemyBoard);
        
        System.out.println(new Gson().toJson(result));
    }
}
```

---

## Testing Checklist

Use `docs/combat-scenarios.md` as your test spec. For each scenario:

```
[ ] Scenario 1: Basic melee (Squire vs Squire)
[ ] Scenario 2: Armor reduction (Knight vs Shieldbearer)
[ ] Scenario 3: Ranger targeting (lowest HP)
[ ] Scenario 4: Knight targeting (nearest)
[ ] Scenario 5: Mage splash (every 3rd action)
[ ] Scenario 6: Healer support (every 3rd action)
[ ] Scenario 7: Time limit (40 seconds)
[ ] Scenario 8: Simultaneous attacks
[ ] Scenario 9: Healing capped at max HP
[ ] Scenario 10: Empty board (immediate defeat)
[ ] Scenario 11: Tie-breaking by ID
[ ] Scenario 12: Shieldbearer vs Mage splash
[ ] Scenario 13: Ranger vs multiple targets
[ ] Scenario 14: Healer self-healing
[ ] Scenario 15: Deterministic replay
```

Each test:
1. Creates a board with specific units
2. Runs CombatEngine.resolve()
3. Asserts on events, final state, Keep damage
4. Matches expected behavior from scenario description

---

## Key Implementation Details

### Determinism requirements

- Use seeded Random for any RNG
- Sort units by ID (always same order)
- Use integer arithmetic (no floats)
- Immutable data structures for results
- No system time dependencies

### Cooldown system

- Initial cooldown: 4 ticks (1 second at 250ms tick)
- Each tick: decrement cooldowns
- When cooldown reaches 0: unit can act
- After action: reset to 4 ticks

### Action counter (for Mage/Healer)

- Increment on each action (move or attack)
- Every 3rd action (isTriggerSpecialAction()): trigger special
- Resets automatically as counter goes 1, 2, 3, 1, 2, 3...

### Keep damage formula

```
damage = 1 + survivor_count + floor(total_hp / 10)
```

Example:
- 0 survivors: 1 + 0 + 0 = 1
- 2 survivors, 15 HP total: 1 + 2 + 1 = 4
- 3 survivors, 45 HP total: 1 + 3 + 4 = 8

---

## Build and Test

```bash
cd backend

# Compile
./mvnw clean compile

# Run all tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=CombatEngineTest

# Run specific test method
./mvnw test -Dtest=CombatEngineTest#scenario_1_squire_vs_squire

# Check coverage
./mvnw clean test jacoco:report
open target/site/jacoco/index.html

# Build JAR
./mvnw package assembly:single
java -jar target/kingdom-tactics-0.1.0-jar-with-dependencies.jar board.json
```

---

## When Phase 1 is complete

- [ ] 30+ tests pass
- [ ] All 15 scenarios pass
- [ ] Code coverage > 85%
- [ ] CLI harness works
- [ ] Determinism verified (same seed = identical outcome)
- [ ] No external dependencies (except test libraries)

Then move to **Phase 2: Backend Foundation**


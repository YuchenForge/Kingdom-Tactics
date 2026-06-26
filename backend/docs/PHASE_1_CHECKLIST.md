# Phase 1: Quick Start Checklist

## ✅ Already Done

- [x] Maven project structure created
- [x] pom.xml configured (JUnit 5, AssertJ, Gson)
- [x] Domain models implemented:
  - [x] UnitDefinition (6 unit types)
  - [x] UnitInstance (position, HP, cooldown)
  - [x] Board (4x4 grid)
  - [x] CombatEvent (event log)
  - [x] ResolutionResult (combat output)
- [x] CombatEngine skeleton (basic structure)
- [x] 3 basic tests

## 🚀 Immediate Next Steps (This Week)

### Task 1: Complete CombatEngine (4-6 hours)

**File:** `backend/src/main/java/com/kingdom/engine/service/CombatEngine.java`

**What to do:**
1. Refactor resolve() to accept both boards: `resolve(Board playerBoard, Board enemyBoard)`
2. Complete the attack handling:
   - [x] Basic damage calculation: max(1, ATK - armor)
   - [ ] Mage splash damage (every 3rd action)
   - [ ] Healer healing (every 3rd action)
3. Fix target selection (currently basic, needs unit-specific logic)
4. Test each change with `./mvnw test`

**Key code sections to implement:**
- Splash damage: Find orthogonal neighbors (4 directions)
- Healing: Find lowest HP ally within range 2
- Movement: Greedy pathfinding toward nearest enemy

### Task 2: Create TargetSelector service (2-3 hours)

**File:** `backend/src/main/java/com/kingdom/engine/service/TargetSelector.java`

**What to do:**
1. Extract target selection logic from CombatEngine
2. Implement unit-specific targeting:
   - Ranger: lowest HP
   - Knight: nearest by Manhattan distance
   - Mage/Healer: lowest HP
   - Others: nearest by Manhattan distance
3. Implement tie-breaking: distance → HP → unit ID
4. Add tests in CombatEngineTest

### Task 3: Write comprehensive tests (8-10 hours)

**File:** `backend/src/test/java/com/kingdom/engine/CombatEngineTest.java`

**What to do:**
Add a test for each scenario from `docs/combat-scenarios.md`:

```java
@Test void scenario_1_basic_melee() { ... }
@Test void scenario_2_armor_reduction() { ... }
@Test void scenario_3_ranger_targeting() { ... }
@Test void scenario_4_knight_targeting() { ... }
@Test void scenario_5_mage_splash() { ... }
@Test void scenario_6_healer_support() { ... }
@Test void scenario_7_time_limit() { ... }
@Test void scenario_8_simultaneous_death() { ... }
@Test void scenario_9_healing_cap() { ... }
@Test void scenario_10_empty_board() { ... }
@Test void scenario_11_tie_breaking_id() { ... }
@Test void scenario_12_shieldbearer_vs_mage() { ... }
@Test void scenario_13_ranger_multiple_targets() { ... }
@Test void scenario_14_healer_self_healing() { ... }
@Test void scenario_15_deterministic_replay() { ... }
```

Each test:
1. Sets up a board with units
2. Calls engine.resolve()
3. Asserts on events/outcome

**Expected time:** 30 min per test = 7.5 hours total

---

## Weekly Goals

**Week 1 of Phase 1:**
- [ ] Finish CombatEngine implementation
- [ ] Write 10-15 of the 15 scenario tests
- [ ] Verify determinism with same-seed tests

**Week 2 of Phase 1:**
- [ ] Write remaining tests
- [ ] Fix any bugs found in testing
- [ ] Achieve > 85% code coverage
- [ ] Clean up code and add comments

**Week 3 of Phase 1 (Optional):**
- [ ] Create CLI harness (java -jar ... board.json)
- [ ] Optimize performance if needed
- [ ] Refactor for reusability

---

## Commands to Know

```bash
# Build and test
cd backend
./mvnw clean compile
./mvnw test

# Run specific test
./mvnw test -Dtest=CombatEngineTest#scenario_1_basic_melee

# Check code coverage
./mvnw jacoco:report
# Coverage report: target/site/jacoco/index.html

# Build for CLI
./mvnw package assembly:single
java -jar target/kingdom-tactics-0.1.0-jar-with-dependencies.jar

# Watch for compilation errors
./mvnw -e compile  # With errors
./mvnw -DskipTests clean package  # Skip tests during build
```

---

## Current Test Output

Run this to see current status:

```bash
cd backend
./mvnw test

# Expected output:
# [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
# (Basic tests pass, ready for more complex scenarios)
```

---

## Document References

**For implementation:**
- `docs/rules.md` — Game mechanics (refer for unit abilities)
- `docs/combat-scenarios.md` — Test specifications (15 scenarios)
- `backend/docs/PHASE_1_GUIDE.md` — Detailed implementation guide

**For design:**
- `docs/adr/003-deterministic-combat.md` — Why determinism matters
- `docs/architecture.md` (section: Engine independence) — Architecture pattern

---

## Red Flags (Things to Avoid)

❌ Don't use floating-point math (all damage is int)  
❌ Don't use system time for randomness (use seeded Random)  
❌ Don't add Spring, JPA, or any frameworks (keep it pure Java)  
❌ Don't forget to increment action counter for Mage/Healer  
❌ Don't break immutability of UnitDefinition/CombatEvent  
❌ Don't forget tie-breaking (distance → HP → unit ID)  

---

## How to Get Unstuck

1. **Scenario doesn't pass?** → Check expected behavior in `docs/combat-scenarios.md`
2. **Test gives wrong damage?** → Verify armor logic (Shieldbearer = 1 armor)
3. **Unit not targeting right?** → Check target selection in TargetSelector
4. **Determinism test fails?** → Ensure all Random calls use seeded RNG
5. **Movement looks wrong?** → Verify greedy pathfinding (closer to target by Manhattan distance)

---

## Estimated Time Breakdown

- CombatEngine completion: 4-6 hours
- TargetSelector service: 2-3 hours
- Unit tests (15 scenarios): 7-10 hours
- Bug fixes and optimization: 3-5 hours
- **Total Phase 1: ~16-24 hours (2-3 days full-time)**

---

## Success Criteria

Phase 1 is complete when:

✅ All 15 scenario tests pass  
✅ Code coverage > 85%  
✅ Determinism verified (same seed = identical events)  
✅ No external dependencies (except test libs)  
✅ All 6 unit abilities implemented  
✅ All tie-breaking rules correct  
✅ Combat completes in < 1 second  

Then you're ready for **Phase 2: Backend Foundation** (Spring Boot, PostgreSQL, REST API)


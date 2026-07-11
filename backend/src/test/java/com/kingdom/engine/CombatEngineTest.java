package com.kingdom.engine;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatEvent;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;
import com.kingdom.engine.service.CombatEngine;

/*
                    Top Player (Rotated 180°)

         x=0         x=1         x=2         x=3
      +-----------+-----------+-----------+-----------+
y=0   | T(3,3)    | T(2,3)    | T(1,3)    | T(0,3)    |
      +-----------+-----------+-----------+-----------+
y=1   | T(3,2)    | T(2,2)    | T(1,2)    | T(0,2)    |
      +-----------+-----------+-----------+-----------+
y=2   | T(3,1)    | T(2,1)    | T(1,1)    | T(0,1)    |
      +-----------+-----------+-----------+-----------+
y=3   | T(3,0)    | T(2,0)    | T(1,0)    | T(0,0)    |
      +===========+===========+===========+===========+
y=4   | B(0,0)    | B(1,0)    | B(2,0)    | B(3,0)    |
      +-----------+-----------+-----------+-----------+
y=5   | B(0,1)    | B(1,1)    | B(2,1)    | B(3,1)    |
      +-----------+-----------+-----------+-----------+
y=6   | B(0,2)    | B(1,2)    | B(2,2)    | B(3,2)    |
      +-----------+-----------+-----------+-----------+
y=7   | B(0,3)    | B(1,3)    | B(2,3)    | B(3,3)    |
      +-----------+-----------+-----------+-----------+
*/

class CombatEngineTest {

    @Test
    void scenario_1_squire_vs_squire() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Local (2,0) → combat (1,3); P1 local (1,2) → combat (1,6); seed 12345
        UnitInstance squire1 = new UnitInstance("unit_001", UnitDefinition.squire(), 2, 0);
        UnitInstance squire2 = new UnitInstance("unit_002", UnitDefinition.squire(), 1, 2);

        playerBoard.addUnit(squire1);
        enemyBoard.addUnit(squire2);

        ResolutionResult result = new CombatEngine(12345L).resolve(playerBoard, enemyBoard);

        assertThat(result.getFinalTick()).isEqualTo(20);
        assertThat(result.getWinnerPlayerId()).isEqualTo(-1);
        assertThat(result.getEndReason()).isEqualTo("DRAW");
        assertThat(result.getKeepDamageForPlayer(0)).isEqualTo(1);
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(1);
        assertThat(result.getFinalBoard().getUnitCountForPlayer(0)).isZero();
        assertThat(result.getFinalBoard().getUnitCountForPlayer(1)).isZero();

        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_MOVED)
            .mapToInt(CombatEvent::getTick)
            .min()).hasValue(3);
        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .mapToInt(CombatEvent::getTick)
            .min()).hasValue(7);
        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_DIED)
            .map(CombatEvent::getTick)
            .distinct()
            .collect(Collectors.toList())).containsExactly(19);
    }

    @Test
    void scenario_2_knight_vs_shieldbearer() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Local (2,3) → combat (1,0); P1 local (0,0) → combat (0,4); seed 54321
        UnitInstance knight = new UnitInstance("unit_001", UnitDefinition.knight(), 2, 3);
        UnitInstance shieldbearer = new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 0, 0);

        playerBoard.addUnit(knight);
        enemyBoard.addUnit(shieldbearer);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> knightAttacksOnSb = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .filter(e -> "unit_002".equals(e.getData().get("targetId")))
            .collect(Collectors.toList());
        List<CombatEvent> sbAttacksOnKnight = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_002".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getFinalTick()).isEqualTo(24);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(3);
        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(13);
        assertThat(knightAttacksOnSb).hasSize(4);
        assertThat(knightAttacksOnSb).allSatisfy(hit ->
            assertThat((int) hit.getData().get("damage")).isEqualTo(4));
        assertThat(sbAttacksOnKnight).hasSize(5);
        assertThat(sbAttacksOnKnight).allSatisfy(hit ->
            assertThat((int) hit.getData().get("damage")).isEqualTo(1));
        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_DIED)
            .filter(e -> "unit_002".equals(e.getData().get("unitId")))
            .map(CombatEvent::getTick)
            .findFirst()).hasValue(23);
    }

    @Test
    void scenario_3_ranger_target_lowest_HP() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Ranger (2,0)→(1,3); SB decoy (0,2)→(0,6); Squire (1,2)→(1,6); seed 54321
        UnitInstance ranger = new UnitInstance("unit_001", UnitDefinition.ranger(), 2, 0);
        UnitInstance highHpEnemy = new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 0, 2);
        UnitInstance lowHpEnemy = new UnitInstance("unit_003", UnitDefinition.squire(), 1, 2);

        playerBoard.addUnit(ranger);
        enemyBoard.addUnit(highHpEnemy);
        enemyBoard.addUnit(lowHpEnemy);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> rangerAttacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        assertThat(rangerAttacks.get(0).getTick()).isEqualTo(3);
        assertThat(rangerAttacks.get(0).getData().get("targetId")).isEqualTo("unit_003");
        assertThat(rangerAttacks.get(0).getData().get("damage")).isEqualTo(4);
        assertThat(rangerAttacks.stream()
            .filter(a -> "unit_003".equals(a.getData().get("targetId")))
            .map(a -> (int) a.getData().get("damage"))
            .collect(Collectors.toList())).containsOnly(4);
        assertThat(rangerAttacks.stream()
            .filter(a -> "unit_002".equals(a.getData().get("targetId")))
            .map(a -> (int) a.getData().get("damage"))
            .collect(Collectors.toList())).containsOnly(3);

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getFinalTick()).isEqualTo(32);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(2);
        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(1);
    }

    @Test
    void scenario_4_knight_target_nearest_enemy() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Knight (3,3)→(0,0); near Squire (0,2)→(0,6); far Squire (1,2)→(1,6); seed 54321
        UnitInstance knight = new UnitInstance("unit_001", UnitDefinition.knight(), 3, 3);
        UnitInstance nearEnemy = new UnitInstance("unit_002", UnitDefinition.squire(), 0, 2);
        UnitInstance farEnemy = new UnitInstance("unit_003", UnitDefinition.squire(), 1, 2);

        playerBoard.addUnit(knight);
        enemyBoard.addUnit(nearEnemy);
        enemyBoard.addUnit(farEnemy);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> knightAttacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        assertThat(knightAttacks.get(0).getTick()).isEqualTo(15);
        assertThat(knightAttacks.get(0).getData().get("targetId")).isEqualTo("unit_002");
        assertThat(knightAttacks.stream()
            .filter(a -> "unit_003".equals(a.getData().get("targetId")))
            .mapToInt(CombatEvent::getTick)
            .min()).hasValue(23);

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getFinalTick()).isEqualTo(28);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(2);
        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(2);
    }

    @Test
    void scenario_5_mage_splash_every_third_attack() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Mage (3,0)→combat (0,3); SB1 (1,0)→(1,4) orthogonal to Mage; SB2 (0,2)→(0,6) farther
        UnitInstance mage = new UnitInstance("unit_001", UnitDefinition.mage(), 3, 0);
        UnitInstance shieldbearer1 = new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 1, 0);
        UnitInstance shieldbearer2 = new UnitInstance("unit_003", UnitDefinition.shieldbearer(), 0, 2);

        playerBoard.addUnit(mage);
        enemyBoard.addUnit(shieldbearer1);
        enemyBoard.addUnit(shieldbearer2);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> mageAttacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        UnitInstance survivingMage = result.getFinalBoard().getUnit("unit_001");

        // Outcome: Mage wins; both shieldbearers eliminated
        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getEndReason()).isEqualTo("PLAYER_VICTORY");
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(2);
        assertThat(result.getFinalBoard().getUnitCountForPlayer(1)).isZero();
        assertThat(survivingMage).isNotNull();
        assertThat(survivingMage.getCurrentHp()).isEqualTo(1);
        assertThat(survivingMage.getActionCounter()).isEqualTo(6);
        assertThat(result.getFinalTick()).isEqualTo(24);

        // Targeting: closer Shieldbearer (unit_002 at orthogonal (1,4)) focused first
        assertThat(mageAttacks.get(0).getData().get("targetId")).isEqualTo("unit_002");

        // Attack 1 (counter=1): direct hit only, no splash
        int firstAttackTick = mageAttacks.get(0).getTick();
        assertThat(firstAttackTick).isEqualTo(3);
        assertThat(mageAttacksOnTick(result, firstAttackTick)).isEqualTo(1);
        assertThat(mageAttacks.get(0).getData().get("damage")).isEqualTo(5); // max(1, 6−1 armor)

        // Attack 2 (counter=2): still no splash
        assertThat(mageAttacksOnTick(result, mageAttacks.get(1).getTick())).isEqualTo(1);
        assertThat(mageAttacks.get(1).getData().get("damage")).isEqualTo(5);

        // Attack 3 (counter=3): primary hit on unit_002 AND splash on orthogonal unit_003
        int thirdAttackTick = mageAttacks.get(2).getTick();
        assertThat(thirdAttackTick).isEqualTo(11);
        assertThat(mageAttacksOnTick(result, thirdAttackTick)).isEqualTo(2);

        List<CombatEvent> thirdAttackHits = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> e.getTick() == thirdAttackTick)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        assertThat(thirdAttackHits).hasSize(2);
        assertThat(thirdAttackHits).anySatisfy(hit -> {
            assertThat(hit.getData().get("targetId")).isEqualTo("unit_002");
            assertThat(hit.getData().get("damage")).isEqualTo(5); // direct: armor applies
        });
        assertThat(thirdAttackHits).anySatisfy(hit -> {
            assertThat(hit.getData().get("targetId")).isEqualTo("unit_003");
            assertThat(hit.getData().get("damage")).isEqualTo(6); // splash: full ATK, armor ignored
        });

        // Shieldbearer1 dies on attack 4; Shieldbearer2 dies on attack 6
        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_DIED)
            .filter(e -> "unit_002".equals(e.getData().get("unitId")))
            .map(CombatEvent::getTick)
            .findFirst()).hasValue(15);
        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_DIED)
            .filter(e -> "unit_003".equals(e.getData().get("unitId")))
            .map(CombatEvent::getTick)
            .findFirst()).hasValue(23);

        // Moves do not increment action counter — Mage in range from start
        long mageMovesBeforeFirstAttack = result.getEvents().stream()
            .takeWhile(e -> !(e.getType() == CombatEvent.EventType.ATTACK
                && "unit_001".equals(e.getData().get("attackerId"))))
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_MOVED
                && "unit_001".equals(e.getData().get("unitId")))
            .count();
        assertThat(mageMovesBeforeFirstAttack).isZero();

        // First actions happen on tick 3 (cooldown), not tick 0
        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK
                && "unit_001".equals(e.getData().get("attackerId")))
            .mapToInt(CombatEvent::getTick)
            .min()).hasValue(3);
    }

    private static long mageAttacksOnTick(ResolutionResult result, int tick) {
        return result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> e.getTick() == tick)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .count();
    }

    @Test
    void scenario_6_healer_support_every_third_attack() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Healer (0,0)→(3,3); Squire (2,0)→(1,3); Enemy Squire (0,1)→(0,5); seed 54321
        UnitInstance healer = new UnitInstance("unit_001", UnitDefinition.healer(), 0, 0);
        UnitInstance squire = new UnitInstance("unit_002", UnitDefinition.squire(), 2, 0);
        UnitInstance enemySquire = new UnitInstance("unit_003", UnitDefinition.squire(), 0, 1);

        playerBoard.addUnit(healer);
        playerBoard.addUnit(squire);
        enemyBoard.addUnit(enemySquire);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> healEvents = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.HEALED)
            .filter(e -> "unit_001".equals(e.getData().get("healerId")))
            .collect(Collectors.toList());

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(4);
        assertThat(result.getFinalBoard().getUnit("unit_001")).isNotNull();
        assertThat(result.getFinalBoard().getUnit("unit_002")).isNotNull();
        assertThat(result.getFinalBoard().getUnitCountForPlayer(1)).isZero();

        assertThat(healEvents).hasSize(1);
        assertThat(healEvents.get(0).getTick()).isEqualTo(15);
        assertThat(healEvents.get(0).getData().get("targetId")).isEqualTo("unit_002");
        assertThat(healEvents.get(0).getData().get("amount")).isEqualTo(5);

        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(10);
        assertThat(result.getFinalBoard().getUnit("unit_002").getCurrentHp()).isEqualTo(5);
        assertThat(result.getFinalTick()).isEqualTo(16);
    }

    @Test
    void scenario_7_time_limit_160_ticks() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Healer (0,0)→(3,3) vs Healer (3,3)→(3,6) — reaches TIME_LIMIT at tick 160 (seed 54321)
        UnitInstance healer0 = new UnitInstance("unit_001", UnitDefinition.healer(), 0, 0);
        UnitInstance healer1 = new UnitInstance("unit_002", UnitDefinition.healer(), 3, 3);

        playerBoard.addUnit(healer0);
        enemyBoard.addUnit(healer1);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        assertThat(result.getFinalTick()).isEqualTo(160);
        assertThat(result.getEndReason()).isEqualTo("TIME_LIMIT");
        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getFinalBoard().getTotalHpForPlayer(0)).isEqualTo(10);
        assertThat(result.getFinalBoard().getTotalHpForPlayer(1)).isEqualTo(10);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(3);
        assertThat(result.getFinalBoard().getUnitCountForPlayer(0)).isEqualTo(1);
        assertThat(result.getFinalBoard().getUnitCountForPlayer(1)).isEqualTo(1);
    }

    @Test
    void scenario_8_same_tick_attacks_and_kill_order() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Knight (3,0)→combat (0,3); Squire (0,0)→combat (0,4); adjacent; seed 111
        UnitInstance knight = new UnitInstance("unit_001", UnitDefinition.knight(), 3, 0);
        UnitInstance squire = new UnitInstance("unit_002", UnitDefinition.squire(), 0, 0);

        playerBoard.addUnit(knight);
        enemyBoard.addUnit(squire);

        ResolutionResult result = new CombatEngine(111L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> tick3Attacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> e.getTick() == 3)
            .collect(Collectors.toList());

        List<CombatEvent> tick7Attacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> e.getTick() == 7)
            .collect(Collectors.toList());

        assertThat(tick3Attacks).hasSize(2);
        assertThat(tick7Attacks).hasSize(2);
        assertThat(tick7Attacks.stream().anyMatch(e -> "unit_002".equals(e.getData().get("attackerId")))).isTrue();

        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .mapToInt(CombatEvent::getTick)
            .min()).hasValue(3);

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getFinalTick()).isEqualTo(8);
        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(14);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(3);
    }

    @Test
    void scenario_9_healing_capped_at_max_hp() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Healer (0,0)→(3,3); ally Squire (1,0)→(2,3); enemy Squire (1,0)→(1,4); seed 54321
        UnitInstance healer = new UnitInstance("unit_001", UnitDefinition.healer(), 0, 0);
        UnitInstance squire = new UnitInstance("unit_002", UnitDefinition.squire(), 1, 0);
        UnitInstance enemySquire = new UnitInstance("unit_003", UnitDefinition.squire(), 1, 0);

        playerBoard.addUnit(healer);
        playerBoard.addUnit(squire);
        enemyBoard.addUnit(enemySquire);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> healEvents = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.HEALED)
            .collect(Collectors.toList());

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getFinalTick()).isEqualTo(12);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(4);
        assertThat(result.getFinalBoard().getUnitCountForPlayer(1)).isZero();

        assertThat(healEvents).hasSize(1);
        assertThat(healEvents.get(0).getTick()).isEqualTo(11);
        assertThat(healEvents.get(0).getData().get("healerId")).isEqualTo("unit_001");
        assertThat(healEvents.get(0).getData().get("targetId")).isEqualTo("unit_002");
        assertThat(healEvents.get(0).getData().get("amount")).isEqualTo(4);

        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(10);
        assertThat(result.getFinalBoard().getUnit("unit_002").getCurrentHp()).isEqualTo(6);

        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_MOVED)
            .filter(e -> "unit_001".equals(e.getData().get("unitId")))
            .count()).isZero();
    }

    @Test
    void scenario_10_empty_board_immediate_defeat() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // P0 empty; P1 Squire at (0,0) → combat (0,4); seed 12345
        UnitInstance squire = new UnitInstance("unit_001", UnitDefinition.squire(), 0, 0);

        enemyBoard.addUnit(squire);

        ResolutionResult result = new CombatEngine(12345L).resolve(playerBoard, enemyBoard);

        long attackEvents = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .count();

        assertThat(result.getFinalTick()).isZero();
        assertThat(result.getWinnerPlayerId()).isEqualTo(1);
        assertThat(result.getEndReason()).isEqualTo("ENEMY_VICTORY");
        assertThat(attackEvents).isZero();
        assertThat(result.getKeepDamageForPlayer(0)).isEqualTo(2);
        assertThat(result.getKeepDamageForPlayer(1)).isZero();
        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(8);
    }

    @Test
    void scenario_11_tie_breaking_by_unit_id() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Knight (2,3)→combat (1,0); Squires at (0,2)→(0,6) and (2,2)→(2,6); seed 54321
        UnitInstance knight = new UnitInstance("unit_001", UnitDefinition.knight(), 2, 3);
        UnitInstance squire1 = new UnitInstance("unit_002", UnitDefinition.squire(), 0, 2);
        UnitInstance squire2 = new UnitInstance("unit_003", UnitDefinition.squire(), 2, 2);

        playerBoard.addUnit(knight);
        enemyBoard.addUnit(squire1);
        enemyBoard.addUnit(squire2);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> knightAttacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        assertThat(knightAttacks.get(0).getTick()).isEqualTo(15);
        assertThat(knightAttacks.get(0).getData().get("targetId")).isEqualTo("unit_002");
        assertThat(knightAttacks.stream()
            .filter(a -> "unit_003".equals(a.getData().get("targetId")))
            .mapToInt(CombatEvent::getTick)
            .min()).hasValue(23);
        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(2);
        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(2);
        assertThat(result.getFinalTick()).isEqualTo(28);
    }

    @Test
    void scenario_12_pre_combat_damage_not_preserved_through_merge() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Same layout as scenario 1; P0 Squire damaged to 3 HP on placement board before merge
        UnitInstance damagedSquire = new UnitInstance("unit_001", UnitDefinition.squire(), 2, 0);
        damagedSquire.takeDamage(5);
        UnitInstance enemySquire = new UnitInstance("unit_002", UnitDefinition.squire(), 1, 2);

        playerBoard.addUnit(damagedSquire);
        enemyBoard.addUnit(enemySquire);

        assertThat(damagedSquire.getCurrentHp()).isEqualTo(3);

        ResolutionResult result = new CombatEngine(12345L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> tick7Attacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> e.getTick() == 7)
            .collect(Collectors.toList());

        assertThat(tick7Attacks).hasSize(2);
        assertThat(tick7Attacks).allSatisfy(hit ->
            assertThat((int) hit.getData().get("damage")).isEqualTo(2));

        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_DIED)
            .map(CombatEvent::getTick)
            .distinct()
            .collect(Collectors.toList())).containsExactly(19);

        assertThat(result.getFinalTick()).isEqualTo(20);
        assertThat(result.getEndReason()).isEqualTo("DRAW");
        assertThat(result.getWinnerPlayerId()).isEqualTo(-1);
        assertThat(result.getKeepDamageForPlayer(0)).isEqualTo(1);
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(1);
    }

    @Test
    void scenario_13_ranger_distance_tie_break() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Ranger (2,0)→(1,3); Squire1 (1,1)→(1,5) Manhattan 2; Squire2 (2,2)→(2,6) Manhattan 4; seed 54321
        UnitInstance ranger = new UnitInstance("unit_001", UnitDefinition.ranger(), 2, 0);
        UnitInstance nearSquire = new UnitInstance("unit_002", UnitDefinition.squire(), 1, 1);
        UnitInstance farSquire = new UnitInstance("unit_003", UnitDefinition.squire(), 2, 2);

        playerBoard.addUnit(ranger);
        enemyBoard.addUnit(nearSquire);
        enemyBoard.addUnit(farSquire);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> rangerAttacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        assertThat(rangerAttacks).hasSize(4);
        assertThat(rangerAttacks.get(0).getTick()).isEqualTo(3);
        assertThat(rangerAttacks.get(0).getData().get("targetId")).isEqualTo("unit_002");
        assertThat(rangerAttacks.get(0).getData().get("damage")).isEqualTo(4);
        assertThat(rangerAttacks.stream()
            .filter(a -> "unit_003".equals(a.getData().get("targetId")))
            .mapToInt(CombatEvent::getTick)
            .min()).hasValue(11);

        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_DIED)
            .filter(e -> "unit_002".equals(e.getData().get("unitId")))
            .map(CombatEvent::getTick)
            .findFirst()).hasValue(7);
        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_DIED)
            .filter(e -> "unit_003".equals(e.getData().get("unitId")))
            .map(CombatEvent::getTick)
            .findFirst()).hasValue(15);

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getFinalTick()).isEqualTo(16);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(2);
        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(1);
    }

    @Test
    void scenario_14_healer_self_heal_when_lowest_hp() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Healer (0,0)→(3,3); Knight (1,1)→(2,2); Squire (2,0) P1→(2,4) — Squire damages Healer in combat
        UnitInstance healer = new UnitInstance("unit_001", UnitDefinition.healer(), 0, 0);
        UnitInstance knight = new UnitInstance("unit_003", UnitDefinition.knight(), 1, 1);
        UnitInstance squire = new UnitInstance("unit_002", UnitDefinition.squire(), 2, 0);

        playerBoard.addUnit(healer);
        playerBoard.addUnit(knight);
        enemyBoard.addUnit(squire);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> selfHeals = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.HEALED)
            .filter(e -> "unit_001".equals(e.getData().get("healerId")))
            .filter(e -> "unit_001".equals(e.getData().get("targetId")))
            .collect(Collectors.toList());

        assertThat(result.getEvents()).anySatisfy(event -> {
            assertThat(event.getTick()).isEqualTo(3);
            assertThat(event.getType()).isEqualTo(CombatEvent.EventType.ATTACK);
            assertThat(event.getData().get("attackerId")).isEqualTo("unit_002");
            assertThat(event.getData().get("targetId")).isEqualTo("unit_001");
            assertThat(event.getData().get("damage")).isEqualTo(2);
        });

        assertThat(selfHeals).hasSize(1);
        assertThat(selfHeals.get(0).getTick()).isEqualTo(11);
        assertThat(selfHeals.get(0).getData().get("amount")).isEqualTo(2);

        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> e.getTick() == 11)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId"))))
            .isEmpty();

        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getFinalTick()).isEqualTo(12);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(5);
        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(10);
        assertThat(result.getFinalBoard().getUnit("unit_003").getCurrentHp()).isEqualTo(14);
    }

    @Test
    void scenario_15_deterministic_replay_full_event_equality() {
        Board board1P = new Board(0);
        Board board1E = new Board(1);
        Board board2P = new Board(0);
        Board board2E = new Board(1);

        // Same setup as scenario 3
        board1P.addUnit(new UnitInstance("unit_001", UnitDefinition.ranger(), 2, 0));
        board1E.addUnit(new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 0, 2));
        board1E.addUnit(new UnitInstance("unit_003", UnitDefinition.squire(), 1, 2));

        board2P.addUnit(new UnitInstance("unit_001", UnitDefinition.ranger(), 2, 0));
        board2E.addUnit(new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 0, 2));
        board2E.addUnit(new UnitInstance("unit_003", UnitDefinition.squire(), 1, 2));

        long seed = 54321L;
        ResolutionResult result1 = new CombatEngine(seed).resolve(board1P, board1E);
        ResolutionResult result2 = new CombatEngine(seed).resolve(board2P, board2E);

        assertThat(result1.getEvents()).isEqualTo(result2.getEvents());
        assertThat(result1.getEvents()).hasSize(23);
        assertThat(result1.getWinnerPlayerId()).isEqualTo(result2.getWinnerPlayerId());
        assertThat(result1.getEndReason()).isEqualTo(result2.getEndReason());
        assertThat(result1.getFinalTick()).isEqualTo(result2.getFinalTick());
        assertThat(result1.getKeepDamageForPlayer(0)).isEqualTo(result2.getKeepDamageForPlayer(0));
        assertThat(result1.getKeepDamageForPlayer(1)).isEqualTo(result2.getKeepDamageForPlayer(1));

        assertThat(result1.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result1.getEndReason()).isEqualTo("PLAYER_VICTORY");
        assertThat(result1.getFinalTick()).isEqualTo(32);
        assertThat(result1.getKeepDamageForPlayer(0)).isZero();
        assertThat(result1.getKeepDamageForPlayer(1)).isEqualTo(2);
        assertThat(result1.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(1);
    }

    // --- Supplementary edge cases ---

    @Test
    void edge_case_first_action_on_tick_3_not_tick_0() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Same as scenario 1 — units start with 4-tick cooldown
        UnitInstance squire1 = new UnitInstance("unit_001", UnitDefinition.squire(), 2, 0);
        UnitInstance squire2 = new UnitInstance("unit_002", UnitDefinition.squire(), 1, 2);

        playerBoard.addUnit(squire1);
        enemyBoard.addUnit(squire2);

        ResolutionResult result = new CombatEngine(12345L).resolve(playerBoard, enemyBoard);

        int firstMoveTick = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_MOVED)
            .mapToInt(CombatEvent::getTick)
            .min()
            .orElse(-1);
        int firstAttackTick = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .mapToInt(CombatEvent::getTick)
            .min()
            .orElse(-1);

        assertThat(firstMoveTick).isEqualTo(3);
        assertThat(firstAttackTick).isEqualTo(7);
        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_MOVED
                || e.getType() == CombatEvent.EventType.ATTACK)
            .mapToInt(CombatEvent::getTick)
            .min()).hasValue(3);
    }

    @Test
    void edge_case_player1_empty_board() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Knight (0,0) local → combat (3,3); P1 empty — mirror of Scenario 10
        playerBoard.addUnit(new UnitInstance("unit_001", UnitDefinition.knight(), 0, 0));

        ResolutionResult result = new CombatEngine(12345L).resolve(playerBoard, enemyBoard);

        assertThat(result.getFinalTick()).isZero();
        assertThat(result.getWinnerPlayerId()).isEqualTo(0);
        assertThat(result.getEndReason()).isEqualTo("PLAYER_VICTORY");
        assertThat(result.getKeepDamageForPlayer(1)).isEqualTo(3);
        assertThat(result.getKeepDamageForPlayer(0)).isZero();
        assertThat(result.getFinalBoard().getUnit("unit_001").getCurrentHp()).isEqualTo(18);
    }

    @Test
    void edge_case_minimum_damage_always_one() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Shieldbearer ATK=1 vs Squire — every hit deals at least 1
        UnitInstance shieldbearer = new UnitInstance("unit_001", UnitDefinition.shieldbearer(), 0, 0);
        UnitInstance squire = new UnitInstance("unit_002", UnitDefinition.squire(), 0, 1);

        playerBoard.addUnit(shieldbearer);
        enemyBoard.addUnit(squire);

        ResolutionResult result = new CombatEngine(12345L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> sbAttacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        assertThat(sbAttacks).isNotEmpty();
        sbAttacks.forEach(a -> assertThat((int) a.getData().get("damage")).isGreaterThanOrEqualTo(1));
    }

    @Test
    void edge_case_mage_moves_do_not_increment_action_counter() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Mage (0,0) far from Shieldbearer (3,3) — moves once before first attack; seed 54321
        UnitInstance mage = new UnitInstance("unit_001", UnitDefinition.mage(), 0, 0);
        UnitInstance shieldbearer = new UnitInstance("unit_002", UnitDefinition.shieldbearer(), 3, 3);

        playerBoard.addUnit(mage);
        enemyBoard.addUnit(shieldbearer);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        long movesBeforeFirstAttack = result.getEvents().stream()
            .takeWhile(e -> e.getType() != CombatEvent.EventType.ATTACK
                || !"unit_001".equals(e.getData().get("attackerId")))
            .filter(e -> e.getType() == CombatEvent.EventType.UNIT_MOVED
                && "unit_001".equals(e.getData().get("unitId")))
            .count();

        List<CombatEvent> mageAttacks = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId")))
            .collect(Collectors.toList());

        assertThat(movesBeforeFirstAttack).isEqualTo(1);
        assertThat(mageAttacks.get(0).getTick()).isEqualTo(7);
        assertThat(mageAttacks.get(2).getTick()).isEqualTo(15);
        assertThat(mageAttacksOnTick(result, mageAttacks.get(2).getTick())).isEqualTo(1);
    }

    @Test
    void edge_case_healer_heal_turn_no_enemy_in_range_required() {
        Board playerBoard = new Board(0);
        Board enemyBoard = new Board(1);

        // Healer far from enemies; ally Knight takes damage — heal turn fires on 3rd attack; seed 54321
        UnitInstance healer = new UnitInstance("unit_001", UnitDefinition.healer(), 0, 0);
        UnitInstance knight = new UnitInstance("unit_002", UnitDefinition.knight(), 3, 3);
        UnitInstance ranger = new UnitInstance("unit_003", UnitDefinition.ranger(), 3, 0);

        playerBoard.addUnit(healer);
        playerBoard.addUnit(knight);
        enemyBoard.addUnit(ranger);

        ResolutionResult result = new CombatEngine(54321L).resolve(playerBoard, enemyBoard);

        List<CombatEvent> healEvents = result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.HEALED)
            .filter(e -> "unit_001".equals(e.getData().get("healerId")))
            .collect(Collectors.toList());

        assertThat(healEvents).isNotEmpty();
        assertThat(healEvents.get(0).getTick()).isEqualTo(11);
        assertThat(healEvents.get(0).getData().get("targetId")).isEqualTo("unit_001");
        assertThat(result.getEvents().stream()
            .filter(e -> e.getType() == CombatEvent.EventType.ATTACK)
            .filter(e -> e.getTick() == 11)
            .filter(e -> "unit_001".equals(e.getData().get("attackerId"))))
            .isEmpty();
    }

    @Test
    void determinism_same_seed_same_output() {
        Board board1P = new Board(0);
        Board board1E = new Board(1);
        Board board2P = new Board(0);
        Board board2E = new Board(1);

        board1P.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 3, 3));
        board1E.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 0, 0));

        board2P.addUnit(new UnitInstance("unit_001", UnitDefinition.squire(), 3, 3));
        board2E.addUnit(new UnitInstance("unit_002", UnitDefinition.squire(), 0, 0));

        long seed = 99999L;
        CombatEngine engine1 = new CombatEngine(seed);
        CombatEngine engine2 = new CombatEngine(seed);

        ResolutionResult result1 = engine1.resolve(board1P, board1E);
        ResolutionResult result2 = engine2.resolve(board2P, board2E);

        assertThat(result1.getEvents().size()).isEqualTo(result2.getEvents().size());
        assertThat(result1.getKeepDamageForPlayer(0)).isEqualTo(result2.getKeepDamageForPlayer(0));
        assertThat(result1.getKeepDamageForPlayer(1)).isEqualTo(result2.getKeepDamageForPlayer(1));
        assertThat(result1.getEndReason()).isEqualTo(result2.getEndReason());
    }
}

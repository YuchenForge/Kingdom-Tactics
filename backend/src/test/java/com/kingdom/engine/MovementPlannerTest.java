package com.kingdom.engine;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;
import com.kingdom.engine.service.MovementPlanner;

class MovementPlannerTest {

    @Test
    void waits_when_all_adjacent_tiles_are_blocked() {
        CombatBoard board = new CombatBoard();
        UnitInstance knight = unit("unit_001", UnitDefinition.knight(), 2, 5, 0);
        UnitInstance northBlocker = unit("unit_003", UnitDefinition.squire(), 2, 4, 0);
        UnitInstance southBlocker = unit("unit_004", UnitDefinition.squire(), 2, 6, 0);
        UnitInstance westBlocker = unit("unit_005", UnitDefinition.squire(), 1, 5, 0);
        UnitInstance eastBlocker = unit("unit_006", UnitDefinition.squire(), 3, 5, 0);
        UnitInstance target = unit("unit_002", UnitDefinition.squire(), 2, 0, 1);

        board.addUnit(knight);
        board.addUnit(northBlocker);
        board.addUnit(southBlocker);
        board.addUnit(westBlocker);
        board.addUnit(eastBlocker);
        board.addUnit(target);

        Optional<int[]> nextStep = MovementPlanner.computeNextStep(knight, target, board);

        assertThat(nextStep).isEmpty();
    }

    @Test
    void routes_around_blocker_toward_attack_position() {
        CombatBoard board = new CombatBoard();
        UnitInstance knight = unit("unit_001", UnitDefinition.knight(), 1, 3, 0);
        UnitInstance blocker = unit("unit_003", UnitDefinition.squire(), 1, 4, 0);
        UnitInstance target = unit("unit_002", UnitDefinition.squire(), 1, 6, 1);

        board.addUnit(knight);
        board.addUnit(blocker);
        board.addUnit(target);

        Optional<int[]> nextStep = MovementPlanner.computeNextStep(knight, target, board);

        assertThat(nextStep).isPresent();
        assertThat(nextStep.get()).isNotEqualTo(new int[] {1, 4});
        assertThat(nextStep.get()).isIn(new int[] {1, 2}, new int[] {0, 3}, new int[] {2, 3});
    }

    @Test
    void moves_toward_closest_reachable_tile_when_attack_position_unreachable() {
        CombatBoard board = new CombatBoard();
        UnitInstance knight = unit("unit_001", UnitDefinition.knight(), 0, 3, 0);
        UnitInstance target = unit("unit_002", UnitDefinition.squire(), 3, 3, 1);

        board.addUnit(knight);
        board.addUnit(target);
        board.addUnit(unit("blocker_e1", UnitDefinition.squire(), 1, 3, 0));
        board.addUnit(unit("blocker_e2", UnitDefinition.squire(), 2, 3, 1));
        board.addUnit(unit("blocker_n", UnitDefinition.squire(), 3, 2, 1));
        board.addUnit(unit("blocker_s", UnitDefinition.squire(), 3, 4, 1));
        board.addUnit(unit("blocker_ne", UnitDefinition.squire(), 2, 2, 1));
        board.addUnit(unit("blocker_se", UnitDefinition.squire(), 2, 4, 1));

        Optional<int[]> nextStep = MovementPlanner.computeNextStep(knight, target, board);

        assertThat(nextStep).isPresent();
        assertThat(nextStep.get()).isEqualTo(new int[] {0, 2});
    }

    @Test
    void attack_positions_use_unit_range() {
        assertThat(MovementPlanner.isAttackPosition(0, 3, 1, 3, 1)).isTrue();
        assertThat(MovementPlanner.isAttackPosition(0, 0, 1, 3, 1)).isFalse();
        assertThat(MovementPlanner.isAttackPosition(0, 0, 2, 2, 3)).isTrue();
        assertThat(MovementPlanner.isAttackPosition(0, 0, 2, 2, 1)).isFalse();
    }

    @Test
    void path_reopens_when_blocker_is_removed() {
        CombatBoard board = new CombatBoard();
        UnitInstance knight = unit("unit_001", UnitDefinition.knight(), 1, 3, 0);
        UnitInstance blocker = unit("unit_003", UnitDefinition.squire(), 1, 4, 0);
        UnitInstance target = unit("unit_002", UnitDefinition.squire(), 1, 6, 1);

        board.addUnit(knight);
        board.addUnit(blocker);
        board.addUnit(target);

        assertThat(MovementPlanner.computeNextStep(knight, target, board).get())
            .isNotEqualTo(new int[] {1, 4});

        board.removeUnit("unit_003");

        assertThat(MovementPlanner.computeNextStep(knight, target, board))
            .contains(new int[] {1, 4});
    }

    @Test
    void tie_breaking_is_deterministic_for_equal_paths() {
        CombatBoard board = new CombatBoard();
        UnitInstance knight = unit("unit_001", UnitDefinition.knight(), 1, 3, 0);
        UnitInstance target = unit("unit_002", UnitDefinition.squire(), 1, 6, 1);

        board.addUnit(knight);
        board.addUnit(target);

        Optional<int[]> first = MovementPlanner.computeNextStep(knight, target, board);
        Optional<int[]> second = MovementPlanner.computeNextStep(knight, target, board);

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(first.get()[0]).isEqualTo(second.get()[0]);
        assertThat(first.get()[1]).isEqualTo(second.get()[1]);
    }

    private static UnitInstance unit(
            String id,
            UnitDefinition definition,
            int x,
            int y,
            int playerId) {
        UnitInstance unit = new UnitInstance(id, definition, x, y);
        unit.setPlayerId(playerId);
        return unit;
    }
}

package com.kingdom.worker.mapper;

import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.mapper.PlanningStateMapper;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;
import com.kingdom.engine.domain.UnitTypeResolver;
import com.kingdom.engine.planning.PlanningUnit;

import java.util.Objects;

/**
 * Builds engine placement Boards from JSONB.
 * Only board_state cells enter combat; lane_units are ignored.
 */
public final class PlanBoardFactory {

    private PlanBoardFactory() {
    }

    public static Board fromPlan(RoundPlan plan, int seat) {
        Objects.requireNonNull(plan, "plan");
        if (seat != 0 && seat != 1) {
            throw new IllegalArgumentException("seat must be 0 or 1, got: " + seat);
        }

        // Build board from board_state cells.
        Board board = new Board(seat);
        PlanningUnit[][] cells = PlanningStateMapper.parseBoard(plan.getBoardState());
        for (int x = 0; x < Board.WIDTH; x++) {
            for (int y = 0; y < Board.HEIGHT; y++) {
                PlanningUnit unit = cells[x][y];
                if (unit == null) {
                    continue;
                }
                UnitDefinition def = UnitTypeResolver.resolve(unit.getType());
                board.addUnit(new UnitInstance(unit.getId(), def, x, y, unit.getLevel()));
            }
        }
        return board;
    }
}

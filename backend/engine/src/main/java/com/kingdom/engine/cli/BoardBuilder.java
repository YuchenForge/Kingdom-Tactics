package com.kingdom.engine.cli;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;

/**
 * Converts CLI-facing DTOs into domain objects. 
 */
public final class BoardBuilder {

    private BoardBuilder() {
        // static utility, not instantiable
    }

    public static Board buildBoard(PlayerDto playerDto) {
        Board board = new Board(playerDto.id);

        for (UnitDto unitDto : playerDto.units) {
            UnitDefinition definition = UnitTypeResolver.resolve(unitDto.type);
            int level = (unitDto.level == null) ? UnitInstance.MIN_LEVEL : unitDto.level;

            UnitInstance unit = new UnitInstance(
                    unitDto.id, definition, unitDto.x, unitDto.y, level);

            board.addUnit(unit);
        }

        return board;
    }
}
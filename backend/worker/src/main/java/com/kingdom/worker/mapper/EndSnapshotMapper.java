package com.kingdom.worker.mapper;

import com.kingdom.api.entity.GameStateSnapshot;
import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.UnitInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure mapper: combat-end survivors -> GameStateSnapshot board JSON (global coords).
 * Lane empty; shop null; is_round_start = false.
 */
public final class EndSnapshotMapper {

    private EndSnapshotMapper() {
    }

    /**
     * Survivor board cells for one seat: [{id,type,level,x,y,currentHp,maxHp}, ...].
     */
    public static List<Object> toBoardJson(CombatBoard finalBoard, int seat) {
        Objects.requireNonNull(finalBoard, "finalBoard");
        if (seat != 0 && seat != 1) {
            throw new IllegalArgumentException("seat must be 0 or 1, got: " + seat);
        }
        List<Object> board = new ArrayList<>();
        for (UnitInstance unit : finalBoard.getAliveUnits()) {
            Integer playerId = unit.getPlayerId();
            if (playerId == null || playerId != seat) {
                continue;
            }
            Map<String, Object> cell = new LinkedHashMap<>(7);
            cell.put("id", unit.getId());
            cell.put("type", unit.getType());
            cell.put("level", unit.getLevel());
            cell.put("x", unit.getX());
            cell.put("y", unit.getY());
            cell.put("currentHp", unit.getCurrentHp());
            cell.put("maxHp", unit.getMaxHp());
            board.add(cell);
        }
        return board;
    }

    /**
     * Round-end snapshot for one player. gold comes from that player's locked plan.
     * keepHp is post-damage Keep HP.
     */
    public static GameStateSnapshot toEndSnapshot(
            UUID gameId,
            int roundNumber,
            UUID playerId,
            int keepHpAfter,
            int gold,
            CombatBoard finalBoard,
            int seat) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(playerId, "playerId");
        return new GameStateSnapshot(
                gameId,
                roundNumber,
                false,
                playerId,
                keepHpAfter,
                gold,
                toBoardJson(finalBoard, seat),
                new ArrayList<>(),
                null);
    }
}

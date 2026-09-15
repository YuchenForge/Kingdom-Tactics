package com.kingdom.api.mapper;

import com.kingdom.api.dto.CommandResponse;
import com.kingdom.api.dto.LaneSlotDto;
import com.kingdom.api.dto.ShopSlotDto;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.HoldingLane;
import com.kingdom.engine.domain.UnitTypeResolver;
import com.kingdom.engine.planning.PlanningShop;
import com.kingdom.engine.planning.PlanningState;
import com.kingdom.engine.planning.PlanningUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JSONB on RoundPlan <-> engine PlanningState.
 * Shop offers stay in shop_offers (passed in as PlanningShop).
 *
 * Board JSON convention: sparse map of "x,y" -> {id, type, level}.
 * Empty join plans use {}; that loads as an empty 4×4. Engine indexing is
 * board[x][y].
 */
public final class PlanningStateMapper {

    private static final String KEY_ID = "id";
    private static final String KEY_TYPE = "type";
    private static final String KEY_LEVEL = "level";

    private PlanningStateMapper() {
    }

    /** DB plan + shop → engine state. */
    public static PlanningState toPlanningState(RoundPlan plan, PlanningShop shop, int roundNumber) {
        return new PlanningState(
                plan.getGold(),
                plan.isLocked(),
                shop,
                parseLane(plan.getLaneUnits()),
                parseBoard(plan.getBoardState()),
                roundNumber);
    }

    /** Write gold / lock / lane / board from engine state onto the JPA entity. */
    public static void applyToPlan(RoundPlan plan, PlanningState state) {
        plan.setGold(state.getGold());
        plan.setLocked(state.isLocked());
        plan.setLaneUnits(serializeLane(state.getLane()));
        plan.setBoardState(serializeBoard(state.getBoard()));
    }

    /** Snapshot for command responses / idempotent retries. */
    public static CommandResponse toSnapshotResponse(PlanningState state) {
        return CommandResponse.snapshot(
                state.getGold(),
                toLaneDtos(state.getLane()),
                toBoardIdGrid(state.getBoard()),
                toShopDtos(state.getShop()),
                state.isLocked());
    }

    public static List<LaneSlotDto> toLaneDtos(PlanningUnit[] lane) {
        List<LaneSlotDto> dtos = new ArrayList<>(HoldingLane.SIZE);
        PlanningUnit[] slots = lane != null ? lane : new PlanningUnit[HoldingLane.SIZE];
        for (int i = 0; i < HoldingLane.SIZE; i++) {
            PlanningUnit unit = i < slots.length ? slots[i] : null;
            if (unit == null) {
                dtos.add(new LaneSlotDto(i, null, null, null));
            } else {
                dtos.add(new LaneSlotDto(i, unit.getId(), unit.getType(), unit.getLevel()));
            }
        }
        return dtos;
    }

    /**
     * API board grid: grid.get(y).get(x) = unit id 
     */
    public static List<List<String>> toBoardIdGrid(PlanningUnit[][] board) {
        List<List<String>> grid = new ArrayList<>(Board.HEIGHT);
        for (int y = 0; y < Board.HEIGHT; y++) {
            List<String> row = new ArrayList<>(Board.WIDTH);
            for (int x = 0; x < Board.WIDTH; x++) {
                PlanningUnit unit = cellAt(board, x, y);
                row.add(unit != null ? unit.getId() : null);
            }
            grid.add(row);
        }
        return grid;
    }

    public static List<ShopSlotDto> toShopDtos(PlanningShop shop) {
        List<ShopSlotDto> dtos = new ArrayList<>(PlanningShop.SLOT_COUNT);
        for (int slot = 0; slot < PlanningShop.SLOT_COUNT; slot++) {
            String type = shop != null ? shop.get(slot) : null;
            if (type == null) {
                dtos.add(new ShopSlotDto(slot, null, 0));
            } else {
                dtos.add(new ShopSlotDto(slot, type, UnitTypeResolver.resolve(type).getCost()));
            }
        }
        return dtos;
    }

    // --- lane ---

    static PlanningUnit[] parseLane(List<Object> laneUnits) {
        PlanningUnit[] lane = new PlanningUnit[HoldingLane.SIZE];
        if (laneUnits == null) {
            return lane;
        }
        int limit = Math.min(HoldingLane.SIZE, laneUnits.size());
        for (int i = 0; i < limit; i++) {
            lane[i] = toUnit(laneUnits.get(i));
        }
        return lane;
    }

    static List<Object> serializeLane(PlanningUnit[] lane) {
        List<Object> out = new ArrayList<>(HoldingLane.SIZE);
        PlanningUnit[] slots = lane != null ? lane : new PlanningUnit[HoldingLane.SIZE];
        for (int i = 0; i < HoldingLane.SIZE; i++) {
            PlanningUnit unit = i < slots.length ? slots[i] : null;
            out.add(unit == null ? null : toMap(unit));
        }
        return out;
    }

    // --- board: sparse "x,y" → unit map (empty join = {}) ---

    static PlanningUnit[][] parseBoard(Map<String, Object> boardState) {
        PlanningUnit[][] board = new PlanningUnit[Board.WIDTH][Board.HEIGHT];
        if (boardState == null || boardState.isEmpty()) {
            return board;
        }
        for (Map.Entry<String, Object> entry : boardState.entrySet()) {
            String key = entry.getKey();
            int[] coords = parseCoordKey(key);
            if (coords == null) {
                continue;
            }
            int x = coords[0];
            int y = coords[1];
            if (!inBounds(x, y)) {
                throw new IllegalArgumentException("board coordinate out of bounds: " + key);
            }
            board[x][y] = toUnit(entry.getValue());
        }
        return board;
    }

    static Map<String, Object> serializeBoard(PlanningUnit[][] board) {
        Map<String, Object> out = new HashMap<>();
        if (board == null) {
            return out;
        }
        for (int x = 0; x < Board.WIDTH; x++) {
            for (int y = 0; y < Board.HEIGHT; y++) {
                PlanningUnit unit = cellAt(board, x, y);
                if (unit != null) {
                    out.put(coordKey(x, y), toMap(unit));
                }
            }
        }
        return out;
    }

    // --- unit JSON ---

    @SuppressWarnings("unchecked")
    static PlanningUnit toUnit(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("unit JSON must be an object, got: " + raw.getClass());
        }
        Object idObj = map.get(KEY_ID);
        Object typeObj = map.get(KEY_TYPE);
        Object levelObj = map.get(KEY_LEVEL);
        if (idObj == null || typeObj == null || levelObj == null) {
            throw new IllegalArgumentException("unit JSON requires id, type, and level");
        }
        if (!(levelObj instanceof Number number)) {
            throw new IllegalArgumentException("unit level must be a number, got: " + levelObj);
        }
        return new PlanningUnit(idObj.toString(), typeObj.toString(), number.intValue());
    }

    static Map<String, Object> toMap(PlanningUnit unit) {
        Map<String, Object> map = new HashMap<>(3);
        map.put(KEY_ID, unit.getId());
        map.put(KEY_TYPE, unit.getType());
        map.put(KEY_LEVEL, unit.getLevel());
        return map;
    }

    private static String coordKey(int x, int y) {
        return x + "," + y;
    }

    private static int[] parseCoordKey(String key) {
        if (key == null) {
            return null;
        }
        int comma = key.indexOf(',');
        if (comma <= 0 || comma >= key.length() - 1) {
            return null;
        }
        try {
            int x = Integer.parseInt(key.substring(0, comma));
            int y = Integer.parseInt(key.substring(comma + 1));
            return new int[] {x, y};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean inBounds(int x, int y) {
        return x >= 0 && x < Board.WIDTH && y >= 0 && y < Board.HEIGHT;
    }

    private static PlanningUnit cellAt(PlanningUnit[][] board, int x, int y) {
        if (board == null || x < 0 || x >= board.length) {
            return null;
        }
        PlanningUnit[] column = board[x];
        if (column == null || y < 0 || y >= column.length) {
            return null;
        }
        return column[y];
    }
}

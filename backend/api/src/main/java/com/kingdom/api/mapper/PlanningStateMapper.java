package com.kingdom.api.mapper;

import com.kingdom.api.dto.CommandResponse;
import com.kingdom.api.dto.LaneSlotDto;
import com.kingdom.api.dto.ShopSlotDto;
import com.kingdom.api.dto.UnitViewDto;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitTypeResolver;
import com.kingdom.engine.planning.PlanningShop;
import com.kingdom.engine.planning.PlanningState;
import com.kingdom.engine.planning.PlanningUnit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSONB on RoundPlan <-> engine PlanningState.
 * Shop offers stay in shop_offers (passed in as PlanningShop).
 *
 * Persisted contract:
 * lane_units: exactly {@link PlanningState#LANE_SIZE} entries (null = empty slot)
 * board_state: sparse map of {@code "x,y"} → {@code {id,type,level}}; {@code {}} = empty
 * Invalid shapes throw rather than pad, truncate, or skip.
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
                toUnitViews(state),
                toShopDtos(state.getShop()),
                state.isLocked());
    }

    public static List<LaneSlotDto> toLaneDtos(PlanningUnit[] lane) {
        requireLaneShape(lane);
        List<LaneSlotDto> dtos = new ArrayList<>(PlanningState.LANE_SIZE);
        for (int i = 0; i < PlanningState.LANE_SIZE; i++) {
            PlanningUnit unit = lane[i];
            if (unit == null) {
                dtos.add(new LaneSlotDto(i, null, null, null));
            } else {
                dtos.add(new LaneSlotDto(i, unit.getId(), unit.getType(), unit.getLevel()));
            }
        }
        return dtos;
    }

    /**
     * API board grid: grid.get(y).get(x) = unit id.
     */
    public static List<List<String>> toBoardIdGrid(PlanningUnit[][] board) {
        requireBoardShape(board);
        List<List<String>> grid = new ArrayList<>(Board.HEIGHT);
        for (int y = 0; y < Board.HEIGHT; y++) {
            List<String> row = new ArrayList<>(Board.WIDTH);
            for (int x = 0; x < Board.WIDTH; x++) {
                PlanningUnit unit = board[x][y];
                row.add(unit != null ? unit.getId() : null);
            }
            grid.add(row);
        }
        return grid;
    }

    /**
     * Board + lane units keyed by id, with level-scaled display stats from the server.
     * Stable order: row-major board cells (y then x), then lane slots 0..N.
     */
    public static Map<String, UnitViewDto> toUnitViews(PlanningState state) {
        Map<String, UnitViewDto> views = new LinkedHashMap<>();
        PlanningUnit[][] board = state.getBoard();
        for (int y = 0; y < Board.HEIGHT; y++) {
            for (int x = 0; x < Board.WIDTH; x++) {
                PlanningUnit unit = board[x][y];
                if (unit != null) {
                    views.put(unit.getId(), toUnitView(unit));
                }
            }
        }
        for (PlanningUnit unit : state.getLane()) {
            if (unit != null) {
                views.put(unit.getId(), toUnitView(unit));
            }
        }
        return views;
    }

    public static UnitViewDto toUnitView(PlanningUnit unit) {
        UnitDefinition def = UnitTypeResolver.resolve(unit.getType());
        int level = unit.getLevel();
        return new UnitViewDto(
                unit.getId(),
                unit.getType(),
                level,
                def.getMaxHp(level),
                def.getAttack(level),
                def.getRange(),
                def.getSpecialAbility(),
                def.getHealAmount(level));
    }

    public static List<ShopSlotDto> toShopDtos(PlanningShop shop) {
        List<ShopSlotDto> dtos = new ArrayList<>(PlanningShop.SLOT_COUNT);
        for (int slot = 0; slot < PlanningShop.SLOT_COUNT; slot++) {
            dtos.add(toShopDto(slot, shop.get(slot)));
        }
        return dtos;
    }

    public static ShopSlotDto toShopDto(int slot, String unitType) {
        if (unitType == null) {
            return new ShopSlotDto(slot, null, 0, 0, 0, 0, null, 0);
        }
        UnitDefinition def = UnitTypeResolver.resolve(unitType);
        return new ShopSlotDto(
                slot,
                unitType,
                def.getCost(),
                def.getMaxHp(1),
                def.getAttack(1),
                def.getRange(),
                def.getSpecialAbility(),
                def.getHealAmount(1));
    }

    // --- lane ---

    static PlanningUnit[] parseLane(List<Object> laneUnits) {
        if (laneUnits == null || laneUnits.size() != PlanningState.LANE_SIZE) {
            throw new IllegalArgumentException(
                    "lane_units must have exactly " + PlanningState.LANE_SIZE
                            + " entries, got "
                            + (laneUnits == null ? "null" : laneUnits.size()));
        }
        PlanningUnit[] lane = new PlanningUnit[PlanningState.LANE_SIZE];
        for (int i = 0; i < PlanningState.LANE_SIZE; i++) {
            lane[i] = toUnit(laneUnits.get(i));
        }
        return lane;
    }

    static List<Object> serializeLane(PlanningUnit[] lane) {
        requireLaneShape(lane);
        List<Object> out = new ArrayList<>(PlanningState.LANE_SIZE);
        for (int i = 0; i < PlanningState.LANE_SIZE; i++) {
            out.add(lane[i] == null ? null : toMap(lane[i]));
        }
        return out;
    }

    // --- board: sparse "x,y" → unit map (empty join = {}) ---

    /** Sparse "x,y" → {id,type,level} → 4×4 local board ({@code {}} = empty). */
    public static PlanningUnit[][] parseBoard(Map<String, Object> boardState) {
        PlanningUnit[][] board = new PlanningUnit[Board.WIDTH][Board.HEIGHT];
        if (boardState == null || boardState.isEmpty()) {
            return board;
        }
        for (Map.Entry<String, Object> entry : boardState.entrySet()) {
            String key = entry.getKey();
            int[] coords = parseCoordKey(key);
            int x = coords[0];
            int y = coords[1];
            if (!Board.isValidPosition(x, y)) {
                throw new IllegalArgumentException("board coordinate out of bounds: " + key);
            }
            board[x][y] = toUnit(entry.getValue());
        }
        return board;
    }

    static Map<String, Object> serializeBoard(PlanningUnit[][] board) {
        requireBoardShape(board);
        Map<String, Object> out = new HashMap<>();
        for (int x = 0; x < Board.WIDTH; x++) {
            for (int y = 0; y < Board.HEIGHT; y++) {
                PlanningUnit unit = board[x][y];
                if (unit != null) {
                    out.put(coordKey(x, y), toMap(unit));
                }
            }
        }
        return out;
    }

    // --- unit JSON ---

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

    private static void requireLaneShape(PlanningUnit[] lane) {
        if (lane == null || lane.length != PlanningState.LANE_SIZE) {
            throw new IllegalArgumentException(
                    "lane must have length " + PlanningState.LANE_SIZE
                            + ", got " + (lane == null ? "null" : lane.length));
        }
    }

    private static void requireBoardShape(PlanningUnit[][] board) {
        if (board == null || board.length != Board.WIDTH) {
            throw new IllegalArgumentException(
                    "board must be " + Board.WIDTH + "×" + Board.HEIGHT);
        }
        for (int x = 0; x < Board.WIDTH; x++) {
            if (board[x] == null || board[x].length != Board.HEIGHT) {
                throw new IllegalArgumentException(
                        "board must be " + Board.WIDTH + "×" + Board.HEIGHT);
            }
        }
    }

    private static String coordKey(int x, int y) {
        return x + "," + y;
    }

    private static int[] parseCoordKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("board coordinate key must be \"x,y\"");
        }
        int comma = key.indexOf(',');
        if (comma <= 0 || comma >= key.length() - 1) {
            throw new IllegalArgumentException("board coordinate key must be \"x,y\": " + key);
        }
        try {
            int x = Integer.parseInt(key.substring(0, comma));
            int y = Integer.parseInt(key.substring(comma + 1));
            return new int[] {x, y};
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("board coordinate key must be \"x,y\": " + key, ex);
        }
    }
}

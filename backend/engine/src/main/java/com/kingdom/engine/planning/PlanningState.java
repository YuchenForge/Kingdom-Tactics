package com.kingdom.engine.planning;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.kingdom.engine.domain.Board;

/**
 * Copy-on-write planning snapshot: gold, lock, shop, lane[5], board 4×4, unit index, round.
 */
public final class PlanningState {
    /** Holding-lane slot count for purchased units awaiting placement. */
    public static final int LANE_SIZE = 5;
    public static final int EARLY_BOARD_CAP = 3;
    public static final int LATE_BOARD_CAP = 5;
    public static final int LATE_BOARD_CAP_FROM_ROUND = 5;

    private final int gold;
    private final boolean locked;
    private final PlanningShop shop;
    private final PlanningUnit[] lane;
    private final PlanningUnit[][] board;
    private final Map<String, PlanningUnit> unitsById;
    private final int roundNumber;

    public PlanningState(
            int gold,
            boolean locked,
            PlanningShop shop,
            PlanningUnit[] lane,
            PlanningUnit[][] board,
            int roundNumber) {
        if (gold < 0) {
            throw new IllegalArgumentException("gold must be >= 0: " + gold);
        }
        if (roundNumber < 1) {
            throw new IllegalArgumentException("roundNumber must be >= 1: " + roundNumber);
        }
        this.gold = gold;
        this.locked = locked;
        this.shop = Objects.requireNonNull(shop, "shop");
        this.lane = copyLane(lane);
        this.board = copyBoard(board);
        this.roundNumber = roundNumber;
        this.unitsById = buildIndex(this.lane, this.board);
    }

    /** Fresh planning state: empty lane/board, given shop and gold. */
    public static PlanningState initial(int gold, PlanningShop shop, int roundNumber) {
        return new PlanningState(
            gold,
            false,
            shop,
            new PlanningUnit[LANE_SIZE],
            new PlanningUnit[Board.WIDTH][Board.HEIGHT],
            roundNumber);
    }

    public int getGold() {
        return gold;
    }

    public boolean isLocked() {
        return locked;
    }

    public PlanningShop getShop() {
        return shop;
    }

    /** Defensive copy of the 5 lane slots (nullable). */
    public PlanningUnit[] getLane() {
        return lane.clone();
    }

    /** Defensive deep copy of the 4×4 board (nullable cells). */
    public PlanningUnit[][] getBoard() {
        return copyBoard(board);
    }

    public Map<String, PlanningUnit> getUnitsById() {
        return Collections.unmodifiableMap(unitsById);
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    /** Max units on the placement board: 3 normally, 5 from round 5 onward. */
    public int boardUnitCap() {
        return PlanningHelpers.boardCap(roundNumber);
    }

    public int boardUnitCount() {
        int count = 0;
        for (int x = 0; x < Board.WIDTH; x++) {
            for (int y = 0; y < Board.HEIGHT; y++) {
                if (board[x][y] != null) {
                    count++;
                }
            }
        }
        return count;
    }

    public boolean isLaneFull() {
        return firstEmptyLaneSlot() < 0;
    }

    public int firstEmptyLaneSlot() {
        for (int i = 0; i < LANE_SIZE; i++) {
            if (lane[i] == null) {
                return i;
            }
        }
        return -1;
    }

    public PlanningUnit getUnit(String unitId) {
        return unitsById.get(unitId);
    }

    public boolean containsUnit(String unitId) {
        return unitsById.containsKey(unitId);
    }

    /** Lane slot index of unitId, or -1 if not on lane. */
    public int findLaneSlot(String unitId) {
        Objects.requireNonNull(unitId, "unitId");
        for (int i = 0; i < LANE_SIZE; i++) {
            if (lane[i] != null && unitId.equals(lane[i].getId())) {
                return i;
            }
        }
        return -1;
    }

    public boolean isOnLane(String unitId) {
        return findLaneSlot(unitId) >= 0;
    }

    public PlanningUnit getBoardUnit(int x, int y) {
        if (!Board.isValidPosition(x, y)) {
            throw new IllegalArgumentException("Invalid position: (" + x + ", " + y + ")");
        }
        return board[x][y];
    }

    /** Board coordinates unitId, or null if not on board. */
    public int[] findBoardPosition(String unitId) {
        Objects.requireNonNull(unitId, "unitId");
        for (int x = 0; x < Board.WIDTH; x++) {
            for (int y = 0; y < Board.HEIGHT; y++) {
                if (board[x][y] != null && unitId.equals(board[x][y].getId())) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    public boolean isOnBoard(String unitId) {
        return findBoardPosition(unitId) != null;
    }

    public PlanningState withGold(int gold) {
        return new PlanningState(gold, locked, shop, lane, board, roundNumber);
    }

    public PlanningState withLocked(boolean locked) {
        return new PlanningState(gold, locked, shop, lane, board, roundNumber);
    }

    public PlanningState withShop(PlanningShop shop) {
        return new PlanningState(gold, locked, shop, lane, board, roundNumber);
    }

    public PlanningState withLane(PlanningUnit[] lane) {
        return new PlanningState(gold, locked, shop, lane, board, roundNumber);
    }

    public PlanningState withBoard(PlanningUnit[][] board) {
        return new PlanningState(gold, locked, shop, lane, board, roundNumber);
    }

    private static PlanningUnit[] copyLane(PlanningUnit[] lane) {
        Objects.requireNonNull(lane, "lane");
        if (lane.length != LANE_SIZE) {
            throw new IllegalArgumentException(
                "lane must have length " + LANE_SIZE + ", got " + lane.length);
        }
        return lane.clone();
    }

    private static PlanningUnit[][] copyBoard(PlanningUnit[][] board) {
        Objects.requireNonNull(board, "board");
        if (board.length != Board.WIDTH) {
            throw new IllegalArgumentException(
                "board width must be " + Board.WIDTH + ", got " + board.length);
        }
        PlanningUnit[][] copy = new PlanningUnit[Board.WIDTH][Board.HEIGHT];
        for (int x = 0; x < Board.WIDTH; x++) {
            if (board[x] == null || board[x].length != Board.HEIGHT) {
                throw new IllegalArgumentException(
                    "board column " + x + " must have height " + Board.HEIGHT);
            }
            copy[x] = board[x].clone();
        }
        return copy;
    }

    private static Map<String, PlanningUnit> buildIndex(
            PlanningUnit[] lane, PlanningUnit[][] board) {
        Map<String, PlanningUnit> index = new HashMap<>();
        for (PlanningUnit unit : lane) {
            putUnique(index, unit);
        }
        for (int x = 0; x < Board.WIDTH; x++) {
            for (int y = 0; y < Board.HEIGHT; y++) {
                putUnique(index, board[x][y]);
            }
        }
        return Map.copyOf(index);
    }

    private static void putUnique(Map<String, PlanningUnit> index, PlanningUnit unit) {
        if (unit == null) {
            return;
        }
        PlanningUnit previous = index.put(unit.getId(), unit);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate unit id: " + unit.getId());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlanningState that)) {
            return false;
        }
        return gold == that.gold
            && locked == that.locked
            && roundNumber == that.roundNumber
            && shop.equals(that.shop)
            && Arrays.equals(lane, that.lane)
            && Arrays.deepEquals(board, that.board);
        // unitsById is derived from lane + board
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(gold, locked, shop, roundNumber);
        result = 31 * result + Arrays.hashCode(lane);
        result = 31 * result + Arrays.deepHashCode(board);
        return result;
    }

    @Override
    public String toString() {
        return "PlanningState{gold=%d, locked=%s, shop=%s, lane=%s, board=%s, roundNumber=%d}"
            .formatted(
                gold,
                locked,
                shop,
                Arrays.toString(lane),
                Arrays.deepToString(board),
                roundNumber);
    }
}

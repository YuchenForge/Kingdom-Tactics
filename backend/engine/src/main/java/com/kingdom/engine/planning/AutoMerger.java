package com.kingdom.engine.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.UnitInstance;

/**
 * Auto-merge after BUY: while ≥3 same (type, level) 
 * 3 across lane + board, consume 3 and place one level+1
 */
public final class AutoMerger {

    /**
     * Applies merge passes until no group of 3+ remains.
     * Upgrade reuses the lowest consumed unit id.
     */
    public PlanningState mergeAll(PlanningState state) {
        Objects.requireNonNull(state, "state");

        PlanningState current = state;
        while (true) {
            List<UnitLoc> trio = findMergeTrio(current);
            if (trio == null) {
                return current;
            }
            current = applyOneMerge(current, trio);
        }
    }

    /**
     * Pick 3 copies: group by (type, level), require count ≥ 3 and level &lt; 3,
     * consume the three with lowest unit ids (ascending).
     */
    private static List<UnitLoc> findMergeTrio(PlanningState state) {
        // TreeMap → deterministic group order by "type|level"
        Map<String, List<UnitLoc>> groups = new TreeMap<>();

        // Collect units on lane
        PlanningUnit[] lane = state.getLane();
        for (int i = 0; i < PlanningState.LANE_SIZE; i++) {
            PlanningUnit unit = lane[i];
            if (unit == null || unit.getLevel() >= UnitInstance.MAX_LEVEL) {
                continue;
            }
            groups.computeIfAbsent(groupKey(unit), k -> new ArrayList<>())
                .add(UnitLoc.onLane(i, unit));
        }

        // Collect units on board
        for (int x = 0; x < Board.WIDTH; x++) {
            for (int y = 0; y < Board.HEIGHT; y++) {
                PlanningUnit unit = state.getBoardUnit(x, y);
                if (unit == null || unit.getLevel() >= UnitInstance.MAX_LEVEL) {
                    continue;
                }
                groups.computeIfAbsent(groupKey(unit), k -> new ArrayList<>())
                    .add(UnitLoc.onBoard(x, y, unit));
            }
        }

        // Find a group of 3 or more units
        for (List<UnitLoc> group : groups.values()) {
            if (group.size() < 3) {
                continue;
            }
            group.sort(Comparator.comparing(loc -> loc.unit().getId()));
            return List.copyOf(group.subList(0, 3));
        }
        return null;
    }

    /**
     * Apply one merge: consume 3 units and place one level+1.
     */
    private static PlanningState applyOneMerge(PlanningState state, List<UnitLoc> trio) {
        // trio sorted by id ascending — reuse lowest id
        PlanningUnit prototype = trio.get(0).unit();
        String mergedId = prototype.getId();
        PlanningUnit merged = new PlanningUnit(
            mergedId, prototype.getType(), prototype.getLevel() + 1);

        PlanningUnit[] lane = state.getLane();
        PlanningUnit[][] board = state.getBoard();

        List<UnitLoc> onBoard = new ArrayList<>();
        int lowestFreedLaneSlot = Integer.MAX_VALUE;

        // Consume the 3 units
        for (UnitLoc loc: trio) {
            if (loc.onLane()) {
                lane[loc.laneSlot()] = null;
                lowestFreedLaneSlot = Math.min(lowestFreedLaneSlot, loc.laneSlot());
            } else {
                board[loc.x()][loc.y()] = null;
                onBoard.add(loc);
            }
        }
        
        // Place the merged unit on the board or the lane
        if (!onBoard.isEmpty()) {
            // Place at consumed board copy with lowest unit id's cell
            onBoard.sort(Comparator.comparing(loc -> loc.unit().getId()));
            UnitLoc anchor = onBoard.get(0);
            board[anchor.x()][anchor.y()] = merged;
        } else {
            // All from lane → first lane slot freed (lowest index)
            lane[lowestFreedLaneSlot] = merged;
        }

        return new PlanningState(
            state.getGold(),
            state.isLocked(),
            state.getShop(),
            lane,
            board,
            state.getRoundNumber());
    }

    /**
     * Generate a key for grouping units by type and level.
     */
    private static String groupKey(PlanningUnit unit) {
        return unit.getType() + "|" + unit.getLevel();
    }

    /**
     * Location of a unit on the lane or the board.
     */
    private record UnitLoc(boolean onLane, int laneSlot, int x, int y, PlanningUnit unit) {
        static UnitLoc onLane(int slot, PlanningUnit unit) {
            return new UnitLoc(true, slot, -1, -1, unit);
        }

        static UnitLoc onBoard(int x, int y, PlanningUnit unit) {
            return new UnitLoc(false, -1, x, y, unit);
        }
    }
}

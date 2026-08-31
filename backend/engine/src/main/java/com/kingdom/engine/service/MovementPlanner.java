package com.kingdom.engine.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.UnitInstance;

/**
 * BFS-based movement planner for combat.
 *
 * Each movement action recomputes the shortest orthogonal path from the unit's current
 * position. The unit first paths toward a reachable attack position; if none exist, it
 * paths toward the closest reachable tile to its target. Multiple shortest paths tie-break
 * with fixed neighbor expansion order (north, south, east, west), then goal selection by
 * Manhattan distance to target, then y, then x.
 */
public final class MovementPlanner {
    private static final int[][] ORTHOGONAL_DIRECTIONS = {
        {0, -1}, // north
        {0, 1},  // south
        {1, 0},  // east
        {-1, 0}, // west
    };

    private MovementPlanner() {
    }

    /**
     * Compute the next orthogonal step for a movement action, or empty if the unit waits.
     */
    public static Optional<int[]> computeNextStep(UnitInstance unit, UnitInstance target, CombatBoard combatBoard) {
        int startX = unit.getX();
        int startY = unit.getY();
        int targetX = target.getX();
        int targetY = target.getY();
        int range = unit.getRange();

        BfsResult bfs = runBfs(startX, startY, combatBoard);

        List<int[]> attackGoals = findReachableAttackPositions(
            targetX, targetY, range, bfs.distances);

        int[] goal = selectBestGoal(attackGoals, bfs.distances, targetX, targetY);
        if (goal == null) {
            goal = selectClosestReachableTile(targetX, targetY, bfs.distances);
        }

        if (goal == null || (goal[0] == startX && goal[1] == startY)) {
            return Optional.empty();
        }

        return Optional.of(firstStepOnPath(startX, startY, goal[0], goal[1], bfs.parentX, bfs.parentY));
    }

    public static boolean isAttackPosition(int x, int y, int targetX, int targetY, int range) {
        if (x == targetX && y == targetY) {
            return false;
        }
        return chebyshevDistance(x, y, targetX, targetY) <= range;
    }

    private static List<int[]> findReachableAttackPositions(int targetX, int targetY, int range, int[][] distances) {
        List<int[]> goals = new ArrayList<>();
        for (int y = 0; y < CombatBoard.HEIGHT; y++) {
            for (int x = 0; x < CombatBoard.WIDTH; x++) {
                if (distances[x][y] >= 0
                        && isAttackPosition(x, y, targetX, targetY, range)) {
                    goals.add(new int[] {x, y});
                }
            }
        }
        return goals;
    }

    private static int[] selectBestGoal(List<int[]> goals, int[][] distances, int targetX, int targetY) {
        if (goals.isEmpty()) {
            return null;
        }

        goals.sort((a, b) -> compareGoalCandidates(a, b, distances, targetX, targetY));
        return goals.get(0);
    }

    private static int[] selectClosestReachableTile(int targetX, int targetY, int[][] distances) {
        List<int[]> candidates = new ArrayList<>();
        int bestManhattan = Integer.MAX_VALUE;

        for (int y = 0; y < CombatBoard.HEIGHT; y++) {
            for (int x = 0; x < CombatBoard.WIDTH; x++) {
                if (distances[x][y] < 0) {
                    continue;
                }
                int manhattan = manhattanDistance(x, y, targetX, targetY);
                if (manhattan < bestManhattan) {
                    bestManhattan = manhattan;
                    candidates.clear();
                    candidates.add(new int[] {x, y});
                } else if (manhattan == bestManhattan) {
                    candidates.add(new int[] {x, y});
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort((a, b) -> compareGoalCandidates(a, b, distances, targetX, targetY));
        return candidates.get(0);
    }

    private static int compareGoalCandidates(int[] a, int[] b, int[][] distances, int targetX, int targetY) {
        int distA = distances[a[0]][a[1]];
        int distB = distances[b[0]][b[1]];
        if (distA != distB) {
            return Integer.compare(distA, distB);
        }

        int manhattanA = manhattanDistance(a[0], a[1], targetX, targetY);
        int manhattanB = manhattanDistance(b[0], b[1], targetX, targetY);
        if (manhattanA != manhattanB) {
            return Integer.compare(manhattanA, manhattanB);
        }
        if (a[1] != b[1]) {
            return Integer.compare(a[1], b[1]);
        }
        return Integer.compare(a[0], b[0]);
    }

    private static int[] firstStepOnPath(int startX, int startY, int goalX, int goalY, int[][] parentX, int[][] parentY) {
        int x = goalX;
        int y = goalY;
        while (!(parentX[x][y] == startX && parentY[x][y] == startY)) {
            int nextX = parentX[x][y];
            int nextY = parentY[x][y];
            x = nextX;
            y = nextY;
        }
        return new int[] {x, y};
    }

    private static BfsResult runBfs(int startX, int startY, CombatBoard combatBoard) {
        int[][] distances = new int[CombatBoard.WIDTH][CombatBoard.HEIGHT];
        int[][] parentX = new int[CombatBoard.WIDTH][CombatBoard.HEIGHT];
        int[][] parentY = new int[CombatBoard.WIDTH][CombatBoard.HEIGHT];

        for (int y = 0; y < CombatBoard.HEIGHT; y++) {
            for (int x = 0; x < CombatBoard.WIDTH; x++) {
                distances[x][y] = -1;
                parentX[x][y] = -1;
                parentY[x][y] = -1;
            }
        }

        ArrayDeque<int[]> queue = new ArrayDeque<>();
        distances[startX][startY] = 0;
        queue.add(new int[] {startX, startY});

        while (!queue.isEmpty()) {
            int[] current = queue.removeFirst();
            int currentX = current[0];
            int currentY = current[1];
            int nextDistance = distances[currentX][currentY] + 1;

            for (int[] direction : ORTHOGONAL_DIRECTIONS) {
                int nextX = currentX + direction[0];
                int nextY = currentY + direction[1];

                if (!CombatBoard.isValidPosition(nextX, nextY)) {
                    continue;
                }
                if (distances[nextX][nextY] >= 0) {
                    continue;
                }
                if (combatBoard.isOccupied(nextX, nextY)) {
                    continue;
                }

                distances[nextX][nextY] = nextDistance;
                parentX[nextX][nextY] = currentX;
                parentY[nextX][nextY] = currentY;
                queue.addLast(new int[] {nextX, nextY});
            }
        }

        return new BfsResult(distances, parentX, parentY);
    }

    private static int chebyshevDistance(int x1, int y1, int x2, int y2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
    }

    private static int manhattanDistance(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    private static final class BfsResult {
        private final int[][] distances;
        private final int[][] parentX;
        private final int[][] parentY;

        private BfsResult(int[][] distances, int[][] parentX, int[][] parentY) {
            this.distances = distances;
            this.parentX = parentX;
            this.parentY = parentY;
        }
    }
}

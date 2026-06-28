package com.kingdom.engine.domain;

/**
 * Coordinate mapping between per-player placement boards and the merged combat board.
 *
 * Placement boards use local coordinates (0–3, 0–3). Combat merges both boards into
 * a 4×8 grid stacked vertically: player 0 on top (rows 0–3), player 1 on bottom
 * (rows 4–7). Column orientation is shared (x=0 left, x=3 right).
 */
public final class Coordinates {
    public static final int PLACEMENT_WIDTH = Board.WIDTH;
    public static final int PLACEMENT_HEIGHT = Board.HEIGHT;
    public static final int COMBAT_WIDTH = 4;
    public static final int COMBAT_HEIGHT = 8;
    public static final int PLAYER_0_ROW_OFFSET = 0;
    public static final int PLAYER_1_ROW_OFFSET = 4;

    private Coordinates() {
    }

    public static boolean isValidPlacement(int x, int y) {
        return Board.isValidPosition(x, y);
    }

    public static boolean isValidCombat(int x, int y) {
        return x >= 0 && x < COMBAT_WIDTH && y >= 0 && y < COMBAT_HEIGHT;
    }

    /**
     * Convert a player's local placement x to global combat x (unchanged for both players).
     */
    public static int toCombatX(int localX, int playerId) {
        validatePlayerId(playerId);
        if (localX < 0 || localX >= PLACEMENT_WIDTH) {
            throw new IllegalArgumentException("Invalid local x: " + localX);
        }
        return localX;
    }

    /**
     * Convert a player's local placement y to global combat y.
     */
    public static int toCombatY(int localY, int playerId) {
        validatePlayerId(playerId);
        if (localY < 0 || localY >= PLACEMENT_HEIGHT) {
            throw new IllegalArgumentException("Invalid local y: " + localY);
        }
        return localY + rowOffset(playerId);
    }

    /**
     * Convert global combat x back to local placement x.
     */
    public static int toLocalX(int combatX, int playerId) {
        validatePlayerId(playerId);
        if (combatX < 0 || combatX >= PLACEMENT_WIDTH) {
            throw new IllegalArgumentException("Invalid combat x: " + combatX);
        }
        return combatX;
    }

    /**
     * Convert global combat y back to local placement y.
     */
    public static int toLocalY(int combatY, int playerId) {
        validatePlayerId(playerId);
        int localY = combatY - rowOffset(playerId);
        if (localY < 0 || localY >= PLACEMENT_HEIGHT) {
            throw new IllegalArgumentException(
                "Combat y " + combatY + " is outside player " + playerId + " half");
        }
        return localY;
    }

    /**
     * Flat cell index for placement board storage (row-major: index = y * 4 + x).
     */
    public static int placementIndex(int x, int y) {
        if (!isValidPlacement(x, y)) {
            throw new IllegalArgumentException("Invalid placement position: (" + x + ", " + y + ")");
        }
        return y * PLACEMENT_WIDTH + x;
    }

    /**
     * Flat cell index for merged combat board storage (row-major: index = y * 4 + x).
     */
    public static int combatIndex(int x, int y) {
        if (!isValidCombat(x, y)) {
            throw new IllegalArgumentException("Invalid combat position: (" + x + ", " + y + ")");
        }
        return y * COMBAT_WIDTH + x;
    }

    private static int rowOffset(int playerId) {
        return playerId == 0 ? PLAYER_0_ROW_OFFSET : PLAYER_1_ROW_OFFSET;
    }

    private static void validatePlayerId(int playerId) {
        if (playerId != 0 && playerId != 1) {
            throw new IllegalArgumentException("playerId must be 0 or 1: " + playerId);
        }
    }
}

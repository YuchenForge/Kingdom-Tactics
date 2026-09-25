package com.kingdom.engine.domain;

/**
 * Coordinate mapping between per-player placement boards and the merged combat board.
 *
 * Placement boards use local coordinates (0–3, 0–3). Combat merges both boards into
 * a 4×8 grid stacked vertically. Player 1 occupies the lower half unchanged. Player 0's
 * board is rotated 180° (chess perspective — facing the opponent) before occupying the
 * upper half: both row and column order are reversed.
 */
public final class Coordinates {
    public static final int PLACEMENT_WIDTH = Board.WIDTH;
    public static final int PLACEMENT_HEIGHT = Board.HEIGHT;
    public static final int COMBAT_WIDTH = 4;
    public static final int COMBAT_HEIGHT = 8;
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
     * Convert a player's local placement x to global combat x.
     * Player 0: columns reversed. Player 1: unchanged.
     */
    public static int toCombatX(int localX, int playerId) {
        validatePlayerId(playerId);
        validateLocalX(localX);
        if (playerId == 0) {
            return (PLACEMENT_WIDTH - 1) - localX;
        }
        return localX;
    }

    /**
     * Convert a player's local placement y to global combat y.
     * Player 0: rows reversed in upper half. Player 1: offset by 4.
     */
    public static int toCombatY(int localY, int playerId) {
        validatePlayerId(playerId);
        validateLocalY(localY);
        if (playerId == 0) {
            return (PLACEMENT_HEIGHT - 1) - localY;
        }
        return localY + PLAYER_1_ROW_OFFSET;
    }

    private static void validatePlayerId(int playerId) {
        if (playerId != 0 && playerId != 1) {
            throw new IllegalArgumentException("playerId must be 0 or 1: " + playerId);
        }
    }

    private static void validateLocalX(int x) {
        if (x < 0 || x >= PLACEMENT_WIDTH) {
            throw new IllegalArgumentException("Invalid local x: " + x);
        }
    }

    private static void validateLocalY(int y) {
        if (y < 0 || y >= PLACEMENT_HEIGHT) {
            throw new IllegalArgumentException("Invalid local y: " + y);
        }
    }
}

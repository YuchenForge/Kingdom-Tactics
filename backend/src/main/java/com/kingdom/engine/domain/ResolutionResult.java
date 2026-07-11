package com.kingdom.engine.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable result of combat resolution.
 * Contains events, final board state, and Keep damage.
 */
public class ResolutionResult {
    private final List<CombatEvent> events;
    private final CombatBoard finalBoard;
    private final int keepDamagePlayer0;
    private final int keepDamagePlayer1;
    private final int finalTick;
    private final String endReason;
    private final int winnerPlayerId;

    public ResolutionResult(
            List<CombatEvent> events,
            CombatBoard finalBoard,
            int[] keepDamageByPlayer,
            int finalTick,
            String endReason,
            int winnerPlayerId) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.finalBoard = new CombatBoard(finalBoard);
        this.keepDamagePlayer0 = keepDamageByPlayer[0];
        this.keepDamagePlayer1 = keepDamageByPlayer[1];
        this.finalTick = finalTick;
        this.endReason = Objects.requireNonNull(endReason);
        this.winnerPlayerId = winnerPlayerId;
    }

    public List<CombatEvent> getEvents() {
        return events;
    }

    public CombatBoard getFinalBoard() {
        return new CombatBoard(finalBoard);
    }

    public int getKeepDamageForPlayer(int playerId) {
        return playerId == 0 ? keepDamagePlayer0 : keepDamagePlayer1;
    }

    public int getFinalTick() {
        return finalTick;
    }

    public String getEndReason() {
        return endReason;
    }

    public int getWinnerPlayerId() {
        return winnerPlayerId;
    }

    /**
     * Per-player Keep damage. Mutual wipe (both boards empty) → each Keep takes 1.
     * Otherwise only the loser's Keep takes damage: 1 + survivor_count + floor(total_hp / 10)
     */
    public static int[] calculateKeepDamageByPlayer(CombatBoard board, int winnerPlayerId) {
        if (board.isEmptyForPlayer(0) && board.isEmptyForPlayer(1)) {
            return new int[]{1, 1};
        }

        int unitCount = board.getUnitCountForPlayer(winnerPlayerId);
        int totalHp = board.getTotalHpForPlayer(winnerPlayerId);
        int loserDamage = 1 + unitCount + (totalHp / 10);

        int[] damage = new int[2];
        damage[winnerPlayerId == 0 ? 1 : 0] = loserDamage;
        return damage;
    }

    @Override
    public String toString() {
        return String.format(
            "ResolutionResult(ticks:%d, events:%d, keepDamage:[%d,%d], reason:%s, winner:%d)",
            finalTick, events.size(), keepDamagePlayer0, keepDamagePlayer1, endReason, winnerPlayerId);
    }
}

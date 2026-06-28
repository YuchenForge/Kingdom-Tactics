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
    private final int keepDamage;
    private final int finalTick;
    private final String endReason;
    private final int winnerPlayerId;

    public ResolutionResult(
            List<CombatEvent> events,
            CombatBoard finalBoard,
            int keepDamage,
            int finalTick,
            String endReason,
            int winnerPlayerId) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.finalBoard = new CombatBoard(finalBoard);
        this.keepDamage = keepDamage;
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

    public int getKeepDamage() {
        return keepDamage;
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
     * damage = 1 + survivor_count + floor(total_hp / 10)
     */
    public static int calculateKeepDamage(CombatBoard board, int winnerPlayerId) {
        int unitCount = board.getUnitCountForPlayer(winnerPlayerId);
        int totalHp = board.getTotalHpForPlayer(winnerPlayerId);
        return 1 + unitCount + (totalHp / 10);
    }

    @Override
    public String toString() {
        return String.format(
            "ResolutionResult(ticks:%d, events:%d, keepDamage:%d, reason:%s, winner:%d)",
            finalTick, events.size(), keepDamage, endReason, winnerPlayerId);
    }
}

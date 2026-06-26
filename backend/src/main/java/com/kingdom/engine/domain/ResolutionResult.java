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
    private final Board finalBoard;
    private final int keepDamage;
    private final int finalTick;
    private final String endReason;

    public ResolutionResult(List<CombatEvent> events, Board finalBoard, int keepDamage, int finalTick, String endReason) {
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.finalBoard = new Board(finalBoard);  // Defensive copy
        this.keepDamage = keepDamage;
        this.finalTick = finalTick;
        this.endReason = Objects.requireNonNull(endReason);
    }

    // Getters
    public List<CombatEvent> getEvents() { return events; }
    public Board getFinalBoard() { return new Board(finalBoard); }
    public int getKeepDamage() { return keepDamage; }
    public int getFinalTick() { return finalTick; }
    public String getEndReason() { return endReason; }

    /**
     * Calculate Keep damage based on formula:
     * damage = 1 + survivor_count + floor(total_hp / 10)
     */
    public static int calculateKeepDamage(Board survivingBoard) {
        int unitCount = survivingBoard.getUnitCount();
        int totalHp = survivingBoard.getTotalHp();
        return 1 + unitCount + (totalHp / 10);
    }

    @Override
    public String toString() {
        return String.format("ResolutionResult(ticks:%d, events:%d, keepDamage:%d, reason:%s)", 
            finalTick, events.size(), keepDamage, endReason);
    }
}

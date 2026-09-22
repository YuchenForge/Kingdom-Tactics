package com.kingdom.worker.service;

/**
 * Pure match-end rules from post–TX2 Keep HP / plan gold (not combat winnerPlayerId).
 *
 * <ol>
 *   <li>Both Keep ≤ 0 → draw</li>
 *   <li>Exactly one Keep ≤ 0 → other seat wins</li>
 *   <li>Round 8, both Keep &gt; 0 → higher Keep; Keep tied → higher gold; gold tied → draw</li>
 *   <li>Else → continue</li>
 * </ol>
 */
public final class MatchEndDecision {

    public static final int FINAL_ROUND = 8;

    private final boolean finished;
    /** Seat 0 or 1 when finished with a winner; null if continue or draw. */
    private final Integer winnerSeat;

    private MatchEndDecision(boolean finished, Integer winnerSeat) {
        this.finished = finished;
        this.winnerSeat = winnerSeat;
    }

    public static MatchEndDecision continueMatch() {
        return new MatchEndDecision(false, null);
    }

    /** {@code winnerSeat} null means draw. */
    public static MatchEndDecision finished(Integer winnerSeat) {
        return new MatchEndDecision(true, winnerSeat);
    }

    public static MatchEndDecision decide(
            int keep0, int keep1, int roundNumber, int gold0, int gold1) {
        boolean dead0 = keep0 <= 0;
        boolean dead1 = keep1 <= 0;

        if (dead0 && dead1) {
            return finished(null);
        }
        if (dead0) {
            return finished(1);
        }
        if (dead1) {
            return finished(0);
        }

        if (roundNumber == FINAL_ROUND) {
            if (keep0 > keep1) {
                return finished(0);
            }
            if (keep1 > keep0) {
                return finished(1);
            }
            if (gold0 > gold1) {
                return finished(0);
            }
            if (gold1 > gold0) {
                return finished(1);
            }
            return finished(null);
        }

        return continueMatch();
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isDraw() {
        return finished && winnerSeat == null;
    }

    /** Seat of the winner, or null if continue / draw. */
    public Integer winnerSeat() {
        return winnerSeat;
    }
}

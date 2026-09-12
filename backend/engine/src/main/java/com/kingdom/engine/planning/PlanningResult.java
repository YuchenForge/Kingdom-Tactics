package com.kingdom.engine.planning;

import java.util.Objects;

/**
 * Result of applying a planning command: ok(PlanningState) | fail(PlanningError).
 */
public final class PlanningResult {
    private final PlanningState state;
    private final PlanningError error;

    private PlanningResult(PlanningState state, PlanningError error) {
        this.state = state;
        this.error = error;
    }

    public static PlanningResult ok(PlanningState state) {
        return new PlanningResult(Objects.requireNonNull(state, "state"), null);
    }

    public static PlanningResult fail(PlanningError error) {
        return new PlanningResult(null, Objects.requireNonNull(error, "error"));
    }

    public boolean isOk() {
        return error == null;
    }

    public boolean isFail() {
        return error != null;
    }

    /** New state after a successful command. Throws if this result is a failure. */
    public PlanningState getState() {
        if (error != null) {
            throw new IllegalStateException("PlanningResult failed: " + error);
        }
        return state;
    }

    /** Failure reason. Throws if this result is ok. */
    public PlanningError getError() {
        if (error == null) {
            throw new IllegalStateException("PlanningResult succeeded");
        }
        return error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlanningResult that)) {
            return false;
        }
        return Objects.equals(state, that.state) && error == that.error;
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, error);
    }

    @Override
    public String toString() {
        return isOk() ? "PlanningResult.ok(" + state + ")" : "PlanningResult.fail(" + error + ")";
    }
}

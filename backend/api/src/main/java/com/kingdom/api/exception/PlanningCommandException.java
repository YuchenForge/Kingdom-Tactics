package com.kingdom.api.exception;

import com.kingdom.engine.planning.PlanningError;

/** Engine planning failure mapped to an HTTP status via PlanningError. */
public class PlanningCommandException extends RuntimeException {

    private final PlanningError error;

    public PlanningCommandException(PlanningError error) {
        super(error.name());
        this.error = error;
    }

    public PlanningError getError() {
        return error;
    }

    public int httpStatus() {
        return error.httpStatus();
    }

    public String errorCode() {
        return error.name();
    }
}

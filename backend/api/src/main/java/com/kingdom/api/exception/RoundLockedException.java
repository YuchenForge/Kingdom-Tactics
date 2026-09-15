package com.kingdom.api.exception;

import java.util.UUID;

/** Game or round already LOCKED — planning commands rejected (423). */
public class RoundLockedException extends RuntimeException {

    private final UUID gameId;

    public RoundLockedException(UUID gameId) {
        super("Round is locked for game " + gameId);
        this.gameId = gameId;
    }

    public UUID getGameId() {
        return gameId;
    }
}

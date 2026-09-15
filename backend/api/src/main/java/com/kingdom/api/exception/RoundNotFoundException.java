package com.kingdom.api.exception;

import java.util.UUID;

/** Round does not exist for this game / round number. */
public class RoundNotFoundException extends RuntimeException {

    private final UUID gameId;
    private final int roundNumber;

    public RoundNotFoundException(UUID gameId, int roundNumber) {
        super("Round " + roundNumber + " not found for game " + gameId);
        this.gameId = gameId;
        this.roundNumber = roundNumber;
    }

    public UUID getGameId() {
        return gameId;
    }

    public int getRoundNumber() {
        return roundNumber;
    }
}

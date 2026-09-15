package com.kingdom.api.exception;

import java.util.UUID;

/** Game/round is not in PREPARATION (or path round is not current). */
public class WrongGameStateException extends RuntimeException {

    private final UUID gameId;

    public WrongGameStateException(UUID gameId, String message) {
        super(message);
        this.gameId = gameId;
    }

    public UUID getGameId() {
        return gameId;
    }
}

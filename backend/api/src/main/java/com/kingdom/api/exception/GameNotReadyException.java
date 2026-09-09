package com.kingdom.api.exception;

import java.util.UUID;

public class GameNotReadyException extends RuntimeException {

    private final UUID gameId;

    public GameNotReadyException(UUID gameId) {
        super("Game is not ready for state reads: " + gameId);
        this.gameId = gameId;
    }

    public UUID getGameId() {
        return gameId;
    }
}

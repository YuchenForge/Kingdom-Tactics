package com.kingdom.api.exception;

import java.util.UUID;

public class GameNotFoundException extends RuntimeException {

    private final UUID gameId;

    public GameNotFoundException(UUID gameId) {
        super("Game not found: " + gameId);
        this.gameId = gameId;
    }

    public UUID getGameId() {
        return gameId;
    }
}

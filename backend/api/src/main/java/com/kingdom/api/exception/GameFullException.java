package com.kingdom.api.exception;

import java.util.UUID;

public class GameFullException extends RuntimeException {

    private final UUID gameId;

    public GameFullException(UUID gameId) {
        super("Game is full or no longer joinable: " + gameId);
        this.gameId = gameId;
    }

    public UUID getGameId() {
        return gameId;
    }
}

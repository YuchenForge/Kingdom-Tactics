package com.kingdom.api.exception;

import java.util.UUID;

public class AlreadyInGameException extends RuntimeException {

    private final UUID gameId;
    private final UUID userId;

    public AlreadyInGameException(UUID gameId, UUID userId) {
        super("User " + userId + " is already in game " + gameId);
        this.gameId = gameId;
        this.userId = userId;
    }

    public UUID getGameId() {
        return gameId;
    }

    public UUID getUserId() {
        return userId;
    }
}

package com.kingdom.api.exception;

import java.util.UUID;

public class NotGameParticipantException extends RuntimeException {

    private final UUID gameId;
    private final UUID userId;

    public NotGameParticipantException(UUID gameId, UUID userId) {
        super("User " + userId + " is not a participant of game " + gameId);
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

package com.kingdom.api.service;

import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.exception.NotGameParticipantException;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class GameService {

    private final GameRepository gameRepository;
    private final GamePlayerRepository gamePlayerRepository;

    public GameService(GameRepository gameRepository, GamePlayerRepository gamePlayerRepository) {
        this.gameRepository = gameRepository;
        this.gamePlayerRepository = gamePlayerRepository;
    }

    // Call at the start of every game-scoped read/action once controllers exist.
    // Skip for POST /games (no game yet) and POST /games/{id}/join (caller not a participant yet).
    public void assertParticipant(UUID gameId, UUID userId) {
        if (!gameRepository.existsById(gameId)) {
            throw new GameNotFoundException(gameId);
        }
        if (!gamePlayerRepository.existsByGameIdAndPlayerId(gameId, userId)) {
            throw new NotGameParticipantException(gameId, userId);
        }
    }
}

package com.kingdom.api.service;

import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.exception.NotGameParticipantException;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GamePlayerRepository gamePlayerRepository;

    private GameService gameService;

    private final UUID gameId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        gameService = new GameService(gameRepository, gamePlayerRepository);
    }

    @Test
    void assertParticipant_rejectsMissingGame() {
        when(gameRepository.existsById(gameId)).thenReturn(false);

        assertThatThrownBy(() -> gameService.assertParticipant(gameId, userId))
                .isInstanceOf(GameNotFoundException.class);

        verifyNoInteractions(gamePlayerRepository);
    }

    @Test
    void assertParticipant_rejectsNonMember() {
        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, userId)).thenReturn(false);

        assertThatThrownBy(() -> gameService.assertParticipant(gameId, userId))
                .isInstanceOf(NotGameParticipantException.class);
    }

    @Test
    void assertParticipant_allowsMember() {
        when(gameRepository.existsById(gameId)).thenReturn(true);
        when(gamePlayerRepository.existsByGameIdAndPlayerId(gameId, userId)).thenReturn(true);

        assertThatCode(() -> gameService.assertParticipant(gameId, userId))
                .doesNotThrowAnyException();
    }
}

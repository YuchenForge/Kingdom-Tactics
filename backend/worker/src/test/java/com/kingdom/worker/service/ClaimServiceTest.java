package com.kingdom.worker.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.engine.service.CombatSeedGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock
    private RoundRepository roundRepository;
    @Mock
    private GameRepository gameRepository;

    private ClaimService claimService;

    private final UUID gameId = UUID.randomUUID();
    private final UUID roundId = UUID.randomUUID();
    private final UUID playerId = UUID.randomUUID();

    private Game game;
    private Round round;

    @BeforeEach
    void setUp() {
        claimService = new ClaimService(roundRepository, gameRepository);

        game = Game.create(playerId);
        ReflectionTestUtils.setField(game, "id", gameId);
        game.setState(GameStates.LOCKED);

        round = new Round(gameId, 1, GameStates.LOCKED, Instant.parse("2024-06-01T12:00:45Z"));
        ReflectionTestUtils.setField(round, "id", roundId);
    }

    @Test
    void claim_whenLockMiss_returnsEmpty() {
        when(roundRepository.lockLockedRoundForClaim(roundId)).thenReturn(Optional.empty());

        Optional<ClaimedRound> result = claimService.claim(roundId);

        assertThat(result).isEmpty();
        verify(gameRepository, never()).lockGameForUpdate(gameId);
    }

    @Test
    void claim_whenLocked_setsResolvingAndSeed() {
        when(roundRepository.lockLockedRoundForClaim(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));

        ClaimedRound claimed = claimService.claim(roundId).orElseThrow();

        long expectedSeed = CombatSeedGenerator.seed(gameId, 1);
        assertThat(round.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(game.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(round.getCombatSeed()).isEqualTo(expectedSeed);
        assertThat(claimed).isEqualTo(new ClaimedRound(roundId, gameId, 1, expectedSeed));
    }

    @Test
    void claim_whenSeedAlreadySet_doesNotChangeSeed() {
        long existing = 42L;
        round.setCombatSeed(existing);
        when(roundRepository.lockLockedRoundForClaim(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));

        ClaimedRound claimed = claimService.claim(roundId).orElseThrow();

        assertThat(round.getCombatSeed()).isEqualTo(existing);
        assertThat(claimed.combatSeed()).isEqualTo(existing);
    }

    @Test
    void claim_whenGameMissing_throws() {
        when(roundRepository.lockLockedRoundForClaim(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> claimService.claim(roundId))
                .isInstanceOf(GameNotFoundException.class);
    }
}

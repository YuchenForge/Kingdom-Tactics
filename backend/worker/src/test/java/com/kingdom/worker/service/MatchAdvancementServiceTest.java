package com.kingdom.worker.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.api.service.GameService;
import com.kingdom.api.service.ShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchAdvancementServiceTest {

    private static final Instant NOW = Instant.parse("2024-06-01T12:30:00Z");

    @Mock
    private RoundRepository roundRepository;
    @Mock
    private GameRepository gameRepository;
    @Mock
    private GamePlayerRepository gamePlayerRepository;
    @Mock
    private RoundPlanRepository roundPlanRepository;
    @Mock
    private ShopService shopService;
    @Mock
    private UserRepository userRepository;

    private MatchAdvancementService service;

    private final UUID roundId = UUID.randomUUID();
    private final UUID gameId = UUID.randomUUID();
    private final UUID player0Id = UUID.randomUUID();
    private final UUID player1Id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MatchAdvancementService(
                roundRepository,
                gameRepository,
                gamePlayerRepository,
                roundPlanRepository,
                shopService,
                userRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void advance_whenLockEmpty_returnsFalse() {
        when(roundRepository.lockUnadvancedRoundResult(roundId)).thenReturn(Optional.empty());

        assertThat(service.advance(roundId)).isFalse();

        verifyNoInteractions(gameRepository, gamePlayerRepository, roundPlanRepository);
    }

    @Test
    void advance_whenGameMissing_throws() {
        Round round = roundResult(1);
        when(roundRepository.lockUnadvancedRoundResult(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.advance(roundId))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void advance_bothAliveBeforeRound8_startsNextPreparation() {
        Round round = roundResult(2);
        Game game = roundResultGame();
        game.setCurrentRound(2);
        GamePlayer p0 = player(0, player0Id, 15);
        GamePlayer p1 = player(1, player1Id, 12);
        RoundPlan old0 = plan(player0Id, 8);
        old0.setLocked(true);
        old0.setLockedAt(NOW.minusSeconds(10));
        old0.setBoardState(new HashMap<>(Map.of("0,0", Map.of("id", "u1", "type", "Squire", "level", 1))));
        old0.setLaneUnits(java.util.Arrays.asList(Map.of("id", "lane-0"), null, null, null, null));
        RoundPlan old1 = plan(player1Id, 6);
        old1.setLocked(true);

        UUID nextRoundId = UUID.randomUUID();
        when(roundRepository.lockUnadvancedRoundResult(roundId)).thenReturn(Optional.of(round));
        when(gameRepository.lockGameForUpdate(gameId)).thenReturn(Optional.of(game));
        when(gamePlayerRepository.lockByGameIdOrderBySeatAsc(gameId)).thenReturn(List.of(p0, p1));
        when(roundPlanRepository.findByRoundId(roundId)).thenReturn(List.of(old0, old1));
        when(roundRepository.save(any(Round.class))).thenAnswer(inv -> {
            Round saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", nextRoundId);
            return saved;
        });

        assertThat(service.advance(roundId)).isTrue();

        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        assertThat(game.getCurrentRound()).isEqualTo(3);
        assertThat(round.getAdvancedAt()).isEqualTo(NOW);
        assertThat(round.getState()).isEqualTo(GameStates.ROUND_RESULT);

        ArgumentCaptor<Round> nextRound = ArgumentCaptor.forClass(Round.class);
        verify(roundRepository).save(nextRound.capture());
        assertThat(nextRound.getValue().getRoundNumber()).isEqualTo(3);
        assertThat(nextRound.getValue().getRoundNumber()).isLessThanOrEqualTo(MatchEndDecision.FINAL_ROUND);
        assertThat(nextRound.getValue().getState()).isEqualTo(GameStates.PREPARATION);
        assertThat(nextRound.getValue().getPlanningDeadline())
                .isEqualTo(NOW.plusSeconds(GameService.PLANNING_SECONDS));

        ArgumentCaptor<RoundPlan> plans = ArgumentCaptor.forClass(RoundPlan.class);
        verify(roundPlanRepository, times(2)).save(plans.capture());
        assertThat(plans.getAllValues()).extracting(RoundPlan::getGold).containsExactlyInAnyOrder(13, 11);
        assertThat(plans.getAllValues()).allMatch(p -> !p.isLocked());
        assertThat(plans.getAllValues()).allMatch(p -> p.getLockedAt() == null);
        assertThat(plans.getAllValues()).extracting(RoundPlan::getRoundId).containsOnly(nextRoundId);

        RoundPlan copied0 = plans.getAllValues().stream()
                .filter(p -> p.getPlayerId().equals(player0Id))
                .findFirst()
                .orElseThrow();
        assertThat(copied0.getBoardState()).isEqualTo(old0.getBoardState());
        assertThat(copied0.getBoardState()).isNotSameAs(old0.getBoardState());
        assertThat(copied0.getLaneUnits()).isEqualTo(old0.getLaneUnits());
        assertThat(old0.getGold()).isEqualTo(8);
        assertThat(old0.isLocked()).isTrue();

        verify(shopService).createShopsForRound(any(Round.class), eq(gameId), eq(player0Id), eq(player1Id));
        verifyNoInteractions(userRepository);
    }

    private Round roundResult(int number) {
        Round round = new Round(gameId, number, GameStates.ROUND_RESULT, NOW);
        ReflectionTestUtils.setField(round, "id", roundId);
        return round;
    }

    private Game roundResultGame() {
        Game game = Game.create(player0Id);
        ReflectionTestUtils.setField(game, "id", gameId);
        game.setPlayer2Id(player1Id);
        game.setState(GameStates.ROUND_RESULT);
        game.setCurrentRound(1);
        return game;
    }

    private static GamePlayer player(int seat, UUID playerId, int keepHp) {
        GamePlayer p = new GamePlayer(UUID.randomUUID(), playerId, seat);
        p.setKeepHp(keepHp);
        return p;
    }

    private RoundPlan plan(UUID playerId, int gold) {
        return new RoundPlan(roundId, playerId, gold);
    }
}

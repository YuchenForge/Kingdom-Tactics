package com.kingdom.api.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingdom.api.dto.GameStateResponse;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameEvent;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStateSnapshot;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.entity.ShopOffer;
import com.kingdom.api.repository.GameEventRepository;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.GameStateSnapshotRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.ShopOfferRepository;
import com.kingdom.api.service.GameService;
import com.kingdom.api.service.ShopService;
import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import com.kingdom.engine.domain.CombatOutcome;
import com.kingdom.engine.planning.PlanningShop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Polling must not mix a pre-TX2/TX3 game.state/round with post-commit Keep HP / plans / shops.
 * {@link GameService} assembles under REPEATABLE READ. These ITs park inside {@code loadShop}
 * (after the game row is read, before Keep HP) so TX2/TX3 can commit mid-assemble.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StatePollConsistencyIT extends AbstractPostgresIT {

    private static final int PRE_TX2_KEEP = 20;
    private static final int POST_TX2_KEEP_ALICE = 18;
    private static final int POST_TX2_KEEP_BOB = 17;
    private static final int ROUND1_GOLD = 10;
    private static final int ROUND2_GOLD = 15;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private GameService gameService;
    @Autowired
    private GameRepository gameRepository;
    @Autowired
    private GamePlayerRepository gamePlayerRepository;
    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private RoundPlanRepository roundPlanRepository;
    @Autowired
    private GameEventRepository gameEventRepository;
    @Autowired
    private GameStateSnapshotRepository snapshotRepository;
    @Autowired
    private ShopOfferRepository shopOfferRepository;
    @SpyBean
    private ShopService shopService;

    @AfterEach
    void resetShopSpy() {
        reset(shopService);
    }

    @Test
    void getState_midTx2Commit_doesNotPairResolvingWithPostCombatKeepHp() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "poll_tx2");
        markResolving(game);
        UUID gameId = UUID.fromString(game.gameId());
        UUID roundId = roundRepository.findByGameIdAndRoundNumber(gameId, 1).orElseThrow().getId();

        CountDownLatch afterGameLoaded = new CountDownLatch(1);
        CountDownLatch tx2Committed = new CountDownLatch(1);
        AtomicBoolean barrierArmed = new AtomicBoolean(true);

        doAnswer(invocation -> {
            if (barrierArmed.compareAndSet(true, false)) {
                afterGameLoaded.countDown();
                assertThat(tx2Committed.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(shopService).loadShop(eq(roundId), eq(game.aliceId()));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<GameStateResponse> poll = pool.submit(
                    () -> gameService.getState(gameId, game.aliceId()));

            assertThat(afterGameLoaded.await(10, TimeUnit.SECONDS)).isTrue();
            commitTx2(game);
            tx2Committed.countDown();

            GameStateResponse response = poll.get(15, TimeUnit.SECONDS);
            assertCoherentTx2Transition(response);
            // Mid-assemble TX2 must stay on the pre-commit snapshot under RR.
            assertThat(response.state()).isEqualTo(GameStates.RESOLVING);
            assertThat(response.yourKeepHp()).isEqualTo(PRE_TX2_KEEP);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void getState_midTx3Advance_doesNotPairNewRoundWithOldPlanGold() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "poll_tx3");
        markResolving(game);
        commitTx2(game);
        UUID gameId = UUID.fromString(game.gameId());
        UUID roundId = roundRepository.findByGameIdAndRoundNumber(gameId, 1).orElseThrow().getId();

        CountDownLatch afterGameLoaded = new CountDownLatch(1);
        CountDownLatch tx3Committed = new CountDownLatch(1);
        AtomicBoolean barrierArmed = new AtomicBoolean(true);

        doAnswer(invocation -> {
            if (barrierArmed.compareAndSet(true, false)) {
                afterGameLoaded.countDown();
                assertThat(tx3Committed.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(shopService).loadShop(eq(roundId), eq(game.aliceId()));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<GameStateResponse> poll = pool.submit(
                    () -> gameService.getState(gameId, game.aliceId()));

            assertThat(afterGameLoaded.await(10, TimeUnit.SECONDS)).isTrue();
            advanceToRound2(game);
            tx3Committed.countDown();

            GameStateResponse response = poll.get(15, TimeUnit.SECONDS);
            assertCoherentTx3Transition(response);
            // Game row was ROUND_RESULT r1 before TX3; RR must not pick up r2 gold.
            assertThat(response.state()).isEqualTo(GameStates.ROUND_RESULT);
            assertThat(response.currentRound()).isEqualTo(1);
            assertThat(response.yourGold()).isEqualTo(ROUND1_GOLD);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void httpPoll_acrossResolveAndAdvance_eachSnapshotIsCoherent() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "poll_http");
        markResolving(game);

        JsonNode resolving = pollState(game);
        assertCoherentTx2Transition(toResponse(resolving));

        commitTx2(game);
        JsonNode roundResult = pollState(game);
        assertThat(roundResult.path("state").asText()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(roundResult.path("yourKeepHp").asInt()).isEqualTo(POST_TX2_KEEP_ALICE);
        assertThat(roundResult.path("latestResolvedRound").asInt()).isEqualTo(1);
        assertThat(roundResult.path("yourGold").asInt()).isEqualTo(ROUND1_GOLD);

        advanceToRound2(game);
        JsonNode preparation = pollState(game);
        assertThat(preparation.path("state").asText()).isEqualTo(GameStates.PREPARATION);
        assertThat(preparation.path("currentRound").asInt()).isEqualTo(2);
        assertThat(preparation.path("yourGold").asInt()).isEqualTo(ROUND2_GOLD);
        assertThat(preparation.path("latestResolvedRound").asInt()).isEqualTo(1);
        assertThat(preparation.path("yourKeepHp").asInt()).isEqualTo(POST_TX2_KEEP_ALICE);
        assertThat(preparation.path("shop")).isNotEmpty();
    }

    private static void assertCoherentTx2Transition(GameStateResponse response) {
        if (GameStates.RESOLVING.equals(response.state())) {
            assertThat(response.yourKeepHp()).isEqualTo(PRE_TX2_KEEP);
            assertThat(response.opponentKeepHp()).isEqualTo(PRE_TX2_KEEP);
            assertThat(response.latestResolvedRound()).isNull();
            assertThat(response.currentRound()).isEqualTo(1);
            assertThat(response.yourGold()).isEqualTo(ROUND1_GOLD);
        } else if (GameStates.ROUND_RESULT.equals(response.state())) {
            assertThat(response.yourKeepHp()).isEqualTo(POST_TX2_KEEP_ALICE);
            assertThat(response.opponentKeepHp()).isEqualTo(POST_TX2_KEEP_BOB);
            assertThat(response.latestResolvedRound()).isEqualTo(1);
            assertThat(response.currentRound()).isEqualTo(1);
            assertThat(response.yourGold()).isEqualTo(ROUND1_GOLD);
        } else {
            throw new AssertionError("Unexpected state during TX2 race: " + response.state());
        }
    }

    private static void assertCoherentTx3Transition(GameStateResponse response) {
        if (GameStates.ROUND_RESULT.equals(response.state())) {
            assertThat(response.currentRound()).isEqualTo(1);
            assertThat(response.yourGold()).isEqualTo(ROUND1_GOLD);
            assertThat(response.latestResolvedRound()).isEqualTo(1);
            assertThat(response.yourKeepHp()).isEqualTo(POST_TX2_KEEP_ALICE);
        } else if (GameStates.PREPARATION.equals(response.state())) {
            assertThat(response.currentRound()).isEqualTo(2);
            assertThat(response.yourGold()).isEqualTo(ROUND2_GOLD);
            assertThat(response.latestResolvedRound()).isEqualTo(1);
            assertThat(response.yourKeepHp()).isEqualTo(POST_TX2_KEEP_ALICE);
        } else {
            throw new AssertionError("Unexpected state during TX3 race: " + response.state());
        }
    }

    private JsonNode pollState(TestAuthSupport.JoinedGame game) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private GameStateResponse toResponse(JsonNode node) throws Exception {
        return objectMapper.treeToValue(node, GameStateResponse.class);
    }

    private void markResolving(TestAuthSupport.JoinedGame game) {
        UUID gameId = UUID.fromString(game.gameId());
        Game g = gameRepository.findById(gameId).orElseThrow();
        g.setState(GameStates.RESOLVING);
        gameRepository.saveAndFlush(g);

        Round round = roundRepository.findByGameIdAndRoundNumber(gameId, 1).orElseThrow();
        round.setState(GameStates.RESOLVING);
        round.setCombatSeed(7L);
        roundRepository.saveAndFlush(round);

        for (RoundPlan plan : roundPlanRepository.findByRoundId(round.getId())) {
            plan.setLocked(true);
            roundPlanRepository.saveAndFlush(plan);
        }
    }

    private void commitTx2(TestAuthSupport.JoinedGame game) {
        UUID gameId = UUID.fromString(game.gameId());
        Game g = gameRepository.findById(gameId).orElseThrow();
        Round round = roundRepository.findByGameIdAndRoundNumber(gameId, 1).orElseThrow();

        g.setState(GameStates.ROUND_RESULT);
        gameRepository.saveAndFlush(g);

        round.setState(GameStates.ROUND_RESULT);
        round.setOutcome(CombatOutcome.ENEMY_VICTORY.name());
        round.setKeepDamage(Map.of("0", 2, "1", 3));
        round.setFinishedAt(Instant.now());
        roundRepository.saveAndFlush(round);

        List<GamePlayer> seats = gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId);
        seats.get(0).setKeepHp(POST_TX2_KEEP_ALICE);
        seats.get(1).setKeepHp(POST_TX2_KEEP_BOB);
        gamePlayerRepository.saveAll(seats);
        gamePlayerRepository.flush();

        gameEventRepository.saveAndFlush(new GameEvent(
                gameId, 1, 1, "COMBAT_ENDED",
                Map.of("reason", CombatOutcome.ENEMY_VICTORY.name()), 5));

        snapshotRepository.saveAndFlush(new GameStateSnapshot(
                gameId, 1, false, game.aliceId(), POST_TX2_KEEP_ALICE, ROUND1_GOLD,
                List.of(), List.of(), null));
        snapshotRepository.saveAndFlush(new GameStateSnapshot(
                gameId, 1, false, game.bobId(), POST_TX2_KEEP_BOB, ROUND1_GOLD,
                List.of(), List.of(), null));
    }

    private void advanceToRound2(TestAuthSupport.JoinedGame game) {
        UUID gameId = UUID.fromString(game.gameId());
        Game g = gameRepository.findById(gameId).orElseThrow();
        Round round1 = roundRepository.findByGameIdAndRoundNumber(gameId, 1).orElseThrow();
        round1.setAdvancedAt(Instant.now());
        roundRepository.saveAndFlush(round1);

        g.setState(GameStates.PREPARATION);
        g.setCurrentRound(2);
        gameRepository.saveAndFlush(g);

        Round round2 = roundRepository.saveAndFlush(new Round(
                gameId, 2, GameStates.PREPARATION, Instant.now().plusSeconds(45)));
        for (UUID playerId : List.of(game.aliceId(), game.bobId())) {
            RoundPlan plan = new RoundPlan(round2.getId(), playerId, ROUND2_GOLD);
            plan.setBoardState(new HashMap<>());
            plan.setLaneUnits(new ArrayList<>(Arrays.asList(null, null, null, null, null)));
            roundPlanRepository.saveAndFlush(plan);

            List<ShopOffer> offers = new ArrayList<>(PlanningShop.SLOT_COUNT);
            for (int slot = 0; slot < PlanningShop.SLOT_COUNT; slot++) {
                offers.add(new ShopOffer(round2.getId(), playerId, slot, "Squire"));
            }
            shopOfferRepository.saveAll(offers);
        }
        shopOfferRepository.flush();
    }
}

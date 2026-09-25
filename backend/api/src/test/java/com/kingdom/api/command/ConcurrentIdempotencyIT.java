package com.kingdom.api.command;

import com.kingdom.api.dto.CommandResponse;
import com.kingdom.api.entity.Command;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.entity.User;
import com.kingdom.api.repository.CommandRepository;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.api.service.CommandService;
import com.kingdom.api.service.ShopService;
import com.kingdom.api.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * Real Postgres: concurrent same Idempotency-Key must complete their transactions
 * (no UnexpectedRollbackException) and converge on one command row.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrentIdempotencyIT extends AbstractPostgresIT {

    @Autowired
    private CommandService commandService;
    @SpyBean
    private ShopService shopService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GameRepository gameRepository;
    @Autowired
    private GamePlayerRepository gamePlayerRepository;
    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private RoundPlanRepository roundPlanRepository;
    @Autowired
    private CommandRepository commandRepository;

    @AfterEach
    void resetShopSpy() {
        reset(shopService);
    }

    @Test
    void concurrentBuy_sameIdempotencyKey_bothSucceed_oneCommandRow() throws Exception {
        Fixture fixture = seedPreparationWithShop();
        UUID key = UUID.randomUUID();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<CommandResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return commandService.buy(
                            fixture.gameId(), 1, fixture.aliceId(), key, 0);
                }));
            }
            start.countDown();

            List<CommandResponse> responses = new ArrayList<>();
            for (Future<CommandResponse> future : futures) {
                responses.add(future.get(15, TimeUnit.SECONDS));
            }

            assertThat(responses).hasSize(2);
            assertThat(responses).allMatch(CommandResponse::success);
            assertThat(responses.get(0).gold()).isEqualTo(responses.get(1).gold());

            List<Command> commands = commandRepository.findAll().stream()
                    .filter(c -> key.equals(c.getIdempotencyKey()))
                    .toList();
            assertThat(commands).hasSize(1);
            assertThat(commands.get(0).getRoundPlanId()).isEqualTo(fixture.planId());

            RoundPlan plan = roundPlanRepository.findById(fixture.planId()).orElseThrow();
            assertThat(plan.getGold()).isEqualTo(responses.get(0).gold());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Controlled interleaving: loser parks inside loadShop until the winner's buy
     * has committed. Loser then sees an empty slot but must recover via the
     * committed idempotency key instead of EMPTY_SHOP_SLOT.
     */
    @Test
    void concurrentBuy_loserSeesSoldSlot_recoversIdempotentSnapshot() throws Exception {
        Fixture fixture = seedPreparationWithShop();
        UUID key = UUID.randomUUID();

        AtomicInteger loadEntrants = new AtomicInteger();
        CountDownLatch loserParkedAtLoadShop = new CountDownLatch(1);
        CountDownLatch winnerFinished = new CountDownLatch(1);

        doAnswer(invocation -> {
            int order = loadEntrants.getAndIncrement();
            if (order == 0) {
                loserParkedAtLoadShop.countDown();
                assertThat(winnerFinished.await(20, TimeUnit.SECONDS)).isTrue();
                return invocation.callRealMethod();
            }
            assertThat(loserParkedAtLoadShop.await(20, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(shopService).loadShop(fixture.roundId(), fixture.aliceId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CommandResponse> first = pool.submit(() -> {
                CommandResponse response = commandService.buy(
                        fixture.gameId(), 1, fixture.aliceId(), key, 0);
                winnerFinished.countDown();
                return response;
            });
            Future<CommandResponse> second = pool.submit(() -> {
                CommandResponse response = commandService.buy(
                        fixture.gameId(), 1, fixture.aliceId(), key, 0);
                winnerFinished.countDown();
                return response;
            });

            CommandResponse a = first.get(25, TimeUnit.SECONDS);
            CommandResponse b = second.get(25, TimeUnit.SECONDS);

            assertThat(a.success()).isTrue();
            assertThat(b.success()).isTrue();
            assertThat(a.gold()).isEqualTo(b.gold());

            List<Command> commands = commandRepository.findAll().stream()
                    .filter(c -> key.equals(c.getIdempotencyKey()))
                    .toList();
            assertThat(commands).hasSize(1);

            RoundPlan plan = roundPlanRepository.findById(fixture.planId()).orElseThrow();
            assertThat(plan.getGold()).isEqualTo(a.gold());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void idempotentRetry_refreshCommitsBetweenPlanAndShop_returnsOneSnapshot() throws Exception {
        Fixture fixture = seedPreparationWithShop();
        UUID buyKey = UUID.randomUUID();
        CommandResponse bought = commandService.buy(
                fixture.gameId(), 1, fixture.aliceId(), buyKey, 0);

        CountDownLatch retryReadPlan = new CountDownLatch(1);
        CountDownLatch refreshCommitted = new CountDownLatch(1);
        AtomicBoolean pauseRetry = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (pauseRetry.compareAndSet(true, false)) {
                // The recovery transaction loaded the plan but has not read the shop.
                retryReadPlan.countDown();
                assertThat(refreshCommitted.await(15, TimeUnit.SECONDS)).isTrue();
            }
            return invocation.callRealMethod();
        }).when(shopService).loadShop(fixture.roundId(), fixture.aliceId());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<CommandResponse> retry = pool.submit(() -> commandService.buy(
                    fixture.gameId(), 1, fixture.aliceId(), buyKey, 0));
            assertThat(retryReadPlan.await(10, TimeUnit.SECONDS)).isTrue();

            CommandResponse refreshed = commandService.refresh(
                    fixture.gameId(), 1, fixture.aliceId(), UUID.randomUUID());
            assertThat(refreshed.gold()).isEqualTo(bought.gold() - 1);
            assertThat(refreshed.shop().get(0).unitType()).isNotNull();
            assertThat(bought.shop().get(0).unitType()).isNull();
            refreshCommitted.countDown();

            // The retry must retain both the pre-refresh gold and pre-refresh shop.
            assertThat(retry.get(15, TimeUnit.SECONDS)).isEqualTo(bought);
            assertThat(roundPlanRepository.findById(fixture.planId()).orElseThrow().getGold())
                    .isEqualTo(refreshed.gold());
            assertThat(commandRepository.countByRoundPlanId(fixture.planId())).isEqualTo(2);
        } finally {
            refreshCommitted.countDown();
            pool.shutdownNow();
        }
    }

    private Fixture seedPreparationWithShop() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User alice = userRepository.save(
                new User("idem_a_" + suffix, "idem_a_" + suffix + "@t.com", new byte[]{1}));
        User bob = userRepository.save(
                new User("idem_b_" + suffix, "idem_b_" + suffix + "@t.com", new byte[]{1}));

        Game game = Game.create(alice.getId());
        game.setPlayer2Id(bob.getId());
        game.setState(GameStates.PREPARATION);
        game.setCurrentRound(1);
        game = gameRepository.save(game);

        gamePlayerRepository.save(new GamePlayer(game.getId(), alice.getId(), 0));
        gamePlayerRepository.save(new GamePlayer(game.getId(), bob.getId(), 1));

        Round round = roundRepository.save(new Round(
                game.getId(), 1, GameStates.PREPARATION, Instant.now().plusSeconds(3600)));

        RoundPlan plan = new RoundPlan(round.getId(), alice.getId(), 10);
        plan.setBoardState(new HashMap<>());
        plan.setLaneUnits(new ArrayList<>(Arrays.asList(null, null, null, null, null)));
        plan = roundPlanRepository.save(plan);

        roundPlanRepository.save(unlockedPlan(round.getId(), bob.getId()));

        shopService.createShopsForRound(round, game.getId(), alice.getId(), bob.getId());

        // Deterministic shop so buy slot 0 always succeeds.
        shopService.persistOffers(round.getId(), alice.getId(), List.of("Squire", "Mage", "Ranger"));

        return new Fixture(game.getId(), round.getId(), plan.getId(), alice.getId());
    }

    private static RoundPlan unlockedPlan(UUID roundId, UUID playerId) {
        RoundPlan plan = new RoundPlan(roundId, playerId, 10);
        plan.setBoardState(new HashMap<>());
        plan.setLaneUnits(new ArrayList<>(Arrays.asList(null, null, null, null, null)));
        return plan;
    }

    private record Fixture(UUID gameId, UUID roundId, UUID planId, UUID aliceId) {
    }
}

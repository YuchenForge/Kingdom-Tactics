package com.kingdom.api.command;

import com.kingdom.api.dto.CommandResponse;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.entity.User;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.api.service.CommandService;
import com.kingdom.api.service.PlanningDeadlineService;
import com.kingdom.api.service.ShopService;
import com.kingdom.api.support.AbstractPostgresIT;
import java.time.Clock;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

/**
 * Concurrent manual locks must still transition PREPARATION → LOCKED.
 * Without round-level serialization both TXs can miss each other's plan lock.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConcurrentLockTransitionIT extends AbstractPostgresIT {

    @SpyBean
    private PlanningDeadlineService planningDeadlineService;
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
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CommandService commandService;
    @Autowired
    private ShopService shopService;
    @SpyBean
    private Clock clock;

    @AfterEach
    void resetSpies() {
        reset(planningDeadlineService, clock);
    }

    @Test
    void deadlineRetry_doesNotOverwriteWorkerClaim() {
        Fixture fixture = seedPreparationRound();
        shopService.persistOffers(fixture.roundId(), fixture.aliceId(), List.of("Squire", "Mage", "Ranger"));
        UUID key = UUID.randomUUID();
        commandService.buy(fixture.gameId(), 1, fixture.aliceId(), key, 0);
        doReturn(Instant.now().plusSeconds(7200)).when(clock).instant();

        TransactionTemplate workerTx = new TransactionTemplate(transactionManager);
        workerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        doAnswer(invocation -> {
            boolean expired = (boolean) invocation.callRealMethod();
            // Deadline finalization has committed; worker claims before retry resumes.
            workerTx.executeWithoutResult(status -> {
                Round round = roundRepository.lockLockedRoundForClaim(fixture.roundId()).orElseThrow();
                Game game = gameRepository.lockGameForUpdate(fixture.gameId()).orElseThrow();
                round.setState(GameStates.RESOLVING);
                game.setState(GameStates.RESOLVING);
            });
            return expired;
        }).when(planningDeadlineService).enforceDeadlineOrAutoLock(any(), any());

        CommandResponse response = commandService.buy(fixture.gameId(), 1, fixture.aliceId(), key, 0);

        assertThat(response.success()).isTrue();
        assertThat(response.isLocked()).isTrue();
        assertThat(response.gold()).isEqualTo(9);
        assertThat(gameRepository.findById(fixture.gameId()).orElseThrow().getState())
                .isEqualTo(GameStates.RESOLVING);
        assertThat(roundRepository.findById(fixture.roundId()).orElseThrow().getState())
                .isEqualTo(GameStates.RESOLVING);
    }

    @Test
    void manualLock_overlappingDeadline_completesWithoutDeadlock() throws Exception {
        Fixture fixture = seedPreparationRound();
        CountDownLatch manualHasTransitionLocks = new CountDownLatch(1);
        CountDownLatch releaseManual = new CountDownLatch(1);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            manualHasTransitionLocks.countDown();
            assertThat(releaseManual.await(20, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(planningDeadlineService).lockForManualCommand(any(), any(), any());


        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CommandResponse> manual = pool.submit(() -> commandService.lock(
                    fixture.gameId(), 1, fixture.aliceId(), UUID.randomUUID()));
            assertThat(manualHasTransitionLocks.await(10, TimeUnit.SECONDS)).isTrue();
            // The deadline expires while the manual request holds its transition locks.
            doReturn(Instant.now().plusSeconds(7200)).when(clock).instant();
            Future<?> deadline = pool.submit(() ->
                    planningDeadlineService.autoLockIfDeadlinePassed(fixture.gameId()));
            long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (waitingOnRoundLock() == 0 && System.nanoTime() < timeout) {
                Thread.sleep(10);
            }
            assertThat(waitingOnRoundLock()).isPositive();
            releaseManual.countDown();
            assertThat(manual.get(15, TimeUnit.SECONDS).isLocked()).isTrue();
            deadline.get(15, TimeUnit.SECONDS);
            assertThat(roundPlanRepository.findByRoundId(fixture.roundId()))
                    .hasSize(2).allMatch(RoundPlan::isLocked);
            assertThat(gameRepository.findById(fixture.gameId()).orElseThrow().getState())
                    .isEqualTo(GameStates.LOCKED);
            assertThat(roundRepository.findById(fixture.roundId()).orElseThrow().getState())
                    .isEqualTo(GameStates.LOCKED);
        } finally {
            releaseManual.countDown();
            pool.shutdownNow();
        }
    }

    private int waitingOnRoundLock() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database()
                  AND wait_event_type = 'Lock' AND query LIKE '%rounds%'
                """, Integer.class);
    }

    @Test
    void simultaneousLocks_bothPlansLocked_roundBecomesLocked() throws Exception {
        Fixture fixture = seedPreparationRound();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        CountDownLatch plansFlushed = new CountDownLatch(2);
        CountDownLatch startTransition = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (UUID playerId : List.of(fixture.aliceId(), fixture.bobId())) {
                futures.add(pool.submit(() -> {
                    try {
                        tx.executeWithoutResult(status -> {
                            RoundPlan plan = roundPlanRepository
                                    .findByRoundIdAndPlayerId(fixture.roundId(), playerId)
                                    .orElseThrow();
                            plan.setLocked(true);
                            plan.setLockedAt(Instant.now());
                            roundPlanRepository.saveAndFlush(plan);

                            plansFlushed.countDown();
                            try {
                                assertThat(startTransition.await(5, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("Interrupted waiting for peer lock flush", e);
                            }

                            Game game = gameRepository.findById(fixture.gameId()).orElseThrow();
                            Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
                            planningDeadlineService.maybeTransitionBothLocked(game, round);
                        });
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        plansFlushed.countDown();
                        startTransition.countDown();
                    }
                }));
            }

            assertThat(plansFlushed.await(5, TimeUnit.SECONDS)).isTrue();
            startTransition.countDown();

            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
            assertThat(failure.get()).isNull();

            Game game = gameRepository.findById(fixture.gameId()).orElseThrow();
            Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
            List<RoundPlan> plans = roundPlanRepository.findByRoundId(fixture.roundId());

            assertThat(plans).hasSize(2).allMatch(RoundPlan::isLocked);
            assertThat(game.getState()).isEqualTo(GameStates.LOCKED);
            assertThat(round.getState()).isEqualTo(GameStates.LOCKED);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Managed PREPARATION entities must not overwrite a newer worker state after
     * {@code FOR UPDATE}. Lock queries refresh; transition then no-ops.
     */
    @Test
    void transitionWithStalePreparation_doesNotOverwriteResolving() {
        Fixture fixture = seedPreparationRound();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Both plans locked + LOCKED game/round (manual transition completed).
        tx.executeWithoutResult(status -> {
            for (UUID playerId : List.of(fixture.aliceId(), fixture.bobId())) {
                RoundPlan plan = roundPlanRepository
                        .findByRoundIdAndPlayerId(fixture.roundId(), playerId)
                        .orElseThrow();
                plan.setLocked(true);
                plan.setLockedAt(Instant.now());
                roundPlanRepository.save(plan);
            }
            Game game = gameRepository.findById(fixture.gameId()).orElseThrow();
            Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
            planningDeadlineService.maybeTransitionBothLocked(game, round);
        });

        tx.executeWithoutResult(status -> {
            Game game = gameRepository.findById(fixture.gameId()).orElseThrow();
            Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
            assertThat(game.getState()).isEqualTo(GameStates.LOCKED);
            assertThat(round.getState()).isEqualTo(GameStates.LOCKED);

            // Bypass JPA so the persistence context keeps LOCKED while DB moves on
            // (worker claim). Then force the in-memory view back to PREPARATION.
            jdbc.update(
                    "UPDATE rounds SET state = ? WHERE id = ?",
                    GameStates.RESOLVING, fixture.roundId());
            jdbc.update(
                    "UPDATE games SET state = ? WHERE id = ?",
                    GameStates.RESOLVING, fixture.gameId());
            game.setState(GameStates.PREPARATION);
            round.setState(GameStates.PREPARATION);

            planningDeadlineService.maybeTransitionBothLocked(game, round);

            // Same managed instances were refreshed to RESOLVING under the lock.
            assertThat(game.getState()).isEqualTo(GameStates.RESOLVING);
            assertThat(round.getState()).isEqualTo(GameStates.RESOLVING);
        });

        Game game = gameRepository.findById(fixture.gameId()).orElseThrow();
        Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
        assertThat(game.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(round.getState()).isEqualTo(GameStates.RESOLVING);
    }

    private Fixture seedPreparationRound() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User alice = userRepository.save(
                new User("clk_a_" + suffix, "clk_a_" + suffix + "@t.com", new byte[]{1}));
        User bob = userRepository.save(
                new User("clk_b_" + suffix, "clk_b_" + suffix + "@t.com", new byte[]{1}));

        Game game = Game.create(alice.getId());
        game.setPlayer2Id(bob.getId());
        game.setState(GameStates.PREPARATION);
        game.setCurrentRound(1);
        game = gameRepository.save(game);

        gamePlayerRepository.save(new GamePlayer(game.getId(), alice.getId(), 0));
        gamePlayerRepository.save(new GamePlayer(game.getId(), bob.getId(), 1));

        Round round = roundRepository.save(new Round(
                game.getId(), 1, GameStates.PREPARATION, Instant.now().plusSeconds(3600)));

        roundPlanRepository.save(unlockedPlan(round.getId(), alice.getId()));
        roundPlanRepository.save(unlockedPlan(round.getId(), bob.getId()));

        shopService.createShopsForRound(round, game.getId(), alice.getId(), bob.getId());

        return new Fixture(game.getId(), round.getId(), alice.getId(), bob.getId());
    }

    private static RoundPlan unlockedPlan(UUID roundId, UUID playerId) {
        RoundPlan plan = new RoundPlan(roundId, playerId, 10);
        plan.setBoardState(new HashMap<>());
        plan.setLaneUnits(new ArrayList<>(Arrays.asList(null, null, null, null, null)));
        return plan;
    }

    private record Fixture(UUID gameId, UUID roundId, UUID aliceId, UUID bobId) {
    }
}

package com.kingdom.worker.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.User;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.engine.service.CombatSeedGenerator;
import com.kingdom.worker.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimServiceIT extends AbstractPostgresIT {

    @Autowired
    private ClaimService claimService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GameRepository gameRepository;
    @Autowired
    private RoundRepository roundRepository;

    @Test
    void claim_setsResolvingAndSeed_secondClaimEmpty() {
        LockedFixture fixture = seedLockedRound();

        ClaimedRound claimed = claimService.claim(fixture.roundId()).orElseThrow();

        assertThat(claimed.combatSeed())
                .isEqualTo(CombatSeedGenerator.seed(fixture.gameId(), 1));

        Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
        Game game = gameRepository.findById(fixture.gameId()).orElseThrow();
        assertThat(round.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(game.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(round.getCombatSeed()).isEqualTo(claimed.combatSeed());
        assertThat(round.getOutcome()).isNull();
        assertThat(round.getKeepDamage()).isNull();
        assertThat(round.getAdvancedAt()).isNull();

        assertThat(claimService.claim(fixture.roundId())).isEmpty();
    }

    @Test
    void claim_twoThreads_exactlyOneSucceeds() throws Exception {
        LockedFixture fixture = seedLockedRound();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Optional<ClaimedRound>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return claimService.claim(fixture.roundId());
                }));
            }
            start.countDown();

            List<Optional<ClaimedRound>> results = new ArrayList<>();
            for (Future<Optional<ClaimedRound>> f : futures) {
                results.add(f.get(10, TimeUnit.SECONDS));
            }

            assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
            assertThat(results.stream().filter(Optional::isEmpty)).hasSize(1);

            Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
            assertThat(round.getState()).isEqualTo(GameStates.RESOLVING);
            assertThat(round.getCombatSeed()).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    // Build a locked round for testing
    private LockedFixture seedLockedRound() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User p1 = userRepository.save(new User("u1_" + suffix, "u1_" + suffix + "@t.com", new byte[]{1}));
        User p2 = userRepository.save(new User("u2_" + suffix, "u2_" + suffix + "@t.com", new byte[]{1}));

        Game game = Game.create(p1.getId());
        game.setPlayer2Id(p2.getId());
        game.setState(GameStates.LOCKED);
        game.setCurrentRound(1);
        game = gameRepository.save(game);

        Round round = new Round(game.getId(), 1, GameStates.LOCKED, Instant.now().plusSeconds(45));
        round = roundRepository.save(round);
        assertThat(round.getCombatSeed()).isNull();

        return new LockedFixture(game.getId(), round.getId());
    }

    private record LockedFixture(UUID gameId, UUID roundId) {
    }
}

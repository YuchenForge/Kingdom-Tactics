package com.kingdom.worker.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.entity.ShopOffer;
import com.kingdom.api.entity.User;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.ShopOfferRepository;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.worker.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TX3 DB effects: continue → PREPARATION N+1; finish → FINISHED + ratings; idempotent under concurrency.
 */
class MatchAdvancementServiceIT extends AbstractPostgresIT {

    @Autowired
    private MatchAdvancementService advancementService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GameRepository gameRepository;
    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private GamePlayerRepository gamePlayerRepository;
    @Autowired
    private RoundPlanRepository roundPlanRepository;
    @Autowired
    private ShopOfferRepository shopOfferRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void advance_continue_createsNextPreparationWithGoldAndShops() {
        Fixture f = seedUnadvanced(1, keep(20, 20), gold(10, 10), boardLane());

        assertThat(advancementService.advance(f.roundId())).isTrue();

        Round completed = roundRepository.findById(f.roundId()).orElseThrow();
        assertThat(completed.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(completed.getAdvancedAt()).isNotNull();
        assertThat(completed.getOutcome()).isEqualTo("DRAW");
        assertThat(completed.getKeepDamage()).isEqualTo(Map.of("0", 0, "1", 0));

        Game game = gameRepository.findById(f.gameId()).orElseThrow();
        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        assertThat(game.getCurrentRound()).isEqualTo(2);
        assertThat(game.getWinnerId()).isNull();
        assertThat(game.getFinishedAt()).isNull();

        Round next = roundRepository.findByGameIdAndRoundNumber(f.gameId(), 2).orElseThrow();
        assertThat(next.getState()).isEqualTo(GameStates.PREPARATION);
        assertThat(next.getCombatSeed()).isNull();
        assertThat(next.getPlanningDeadline()).isAfter(Instant.now().minusSeconds(1));

        // Old plans intact
        List<RoundPlan> oldPlans = roundPlanRepository.findByRoundId(f.roundId());
        assertThat(oldPlans).hasSize(2);
        assertThat(oldPlans).allMatch(RoundPlan::isLocked);
        assertThat(oldPlans).extracting(RoundPlan::getGold).containsOnly(10);

        // New plans: unlocked, gold+5, copied board/lane
        List<RoundPlan> newPlans = roundPlanRepository.findByRoundId(next.getId());
        assertThat(newPlans).hasSize(2);
        assertThat(newPlans).allMatch(p -> !p.isLocked());
        assertThat(newPlans).allMatch(p -> p.getLockedAt() == null);
        assertThat(newPlans).extracting(RoundPlan::getGold).containsExactlyInAnyOrder(15, 15);
        RoundPlan new0 = newPlans.stream()
                .filter(p -> p.getPlayerId().equals(f.player0Id()))
                .findFirst()
                .orElseThrow();
        assertThat(new0.getBoardState()).isEqualTo(boardLane().board());
        assertThat(new0.getLaneUnits()).isEqualTo(boardLane().lane());

        // Fresh shops (6 = 2 players × 3 slots); none on old round
        assertThat(shopCount(next.getId())).isEqualTo(6);
        assertThat(shopCount(f.roundId())).isZero();
        List<ShopOffer> offers = shopOfferRepository.findByRoundIdAndPlayerIdOrderBySlotAsc(
                next.getId(), f.player0Id());
        assertThat(offers).hasSize(3);
        assertThat(offers).allMatch(o -> o.getUnitType() != null);
    }

    @Test
    void advance_secondCall_noOps_noDuplicateRoundOrShops() {
        Fixture f = seedUnadvanced(1, keep(20, 20), gold(10, 10), boardLane());

        assertThat(advancementService.advance(f.roundId())).isTrue();
        Instant advancedAt = roundRepository.findById(f.roundId()).orElseThrow().getAdvancedAt();
        int shopsAfterFirst = shopCountForGame(f.gameId());

        assertThat(advancementService.advance(f.roundId())).isFalse();

        assertThat(roundRepository.findById(f.roundId()).orElseThrow().getAdvancedAt())
                .isEqualTo(advancedAt);
        assertThat(roundRepository.findByGameIdAndRoundNumber(f.gameId(), 3)).isEmpty();
        assertThat(shopCountForGame(f.gameId())).isEqualTo(shopsAfterFirst);

        List<RoundPlan> round2Plans = roundPlanRepository.findByRoundId(
                roundRepository.findByGameIdAndRoundNumber(f.gameId(), 2).orElseThrow().getId());
        assertThat(round2Plans).extracting(RoundPlan::getGold).containsOnly(15);
    }

    @Test
    void advance_keepKo_finishesWithRatings() {
        Fixture f = seedUnadvanced(3, keep(0, 12), gold(10, 10), emptyBoard());
        int rating0 = userRepository.findById(f.player0Id()).orElseThrow().getRating();
        int rating1 = userRepository.findById(f.player1Id()).orElseThrow().getRating();

        assertThat(advancementService.advance(f.roundId())).isTrue();

        Round completed = roundRepository.findById(f.roundId()).orElseThrow();
        Game game = gameRepository.findById(f.gameId()).orElseThrow();
        assertThat(completed.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(completed.getAdvancedAt()).isNotNull();
        assertThat(game.getState()).isEqualTo(GameStates.FINISHED);
        assertThat(game.getWinnerId()).isEqualTo(f.player1Id());
        assertThat(game.getFinishedAt()).isNotNull();
        assertThat(roundRepository.findByGameIdAndRoundNumber(f.gameId(), 4)).isEmpty();

        assertThat(userRepository.findById(f.player0Id()).orElseThrow().getRating())
                .isEqualTo(rating0 - MatchAdvancementService.RATING_DELTA);
        assertThat(userRepository.findById(f.player1Id()).orElseThrow().getRating())
                .isEqualTo(rating1 + MatchAdvancementService.RATING_DELTA);
    }

    @Test
    void advance_draw_winnerNull_ratingsUnchanged() {
        Fixture f = seedUnadvanced(2, keep(0, 0), gold(10, 10), emptyBoard());
        int rating0 = userRepository.findById(f.player0Id()).orElseThrow().getRating();
        int rating1 = userRepository.findById(f.player1Id()).orElseThrow().getRating();

        assertThat(advancementService.advance(f.roundId())).isTrue();

        Game game = gameRepository.findById(f.gameId()).orElseThrow();
        assertThat(game.getState()).isEqualTo(GameStates.FINISHED);
        assertThat(game.getWinnerId()).isNull();
        assertThat(userRepository.findById(f.player0Id()).orElseThrow().getRating()).isEqualTo(rating0);
        assertThat(userRepository.findById(f.player1Id()).orElseThrow().getRating()).isEqualTo(rating1);
        assertThat(roundRepository.findByGameIdAndRoundNumber(f.gameId(), 3)).isEmpty();
    }

    @Test
    void advance_round8_bothAlive_finishesByKeepThenGold() {
        // Keep 12 vs 10 → seat 0 wins
        Fixture byKeep = seedUnadvanced(8, keep(12, 10), gold(1, 99), emptyBoard());
        assertThat(advancementService.advance(byKeep.roundId())).isTrue();
        assertThat(gameRepository.findById(byKeep.gameId()).orElseThrow().getWinnerId())
                .isEqualTo(byKeep.player0Id());
        assertThat(roundRepository.findByGameIdAndRoundNumber(byKeep.gameId(), 9)).isEmpty();

        // Keep tied, gold 8 vs 3 → seat 0 wins
        Fixture byGold = seedUnadvanced(8, keep(10, 10), gold(8, 3), emptyBoard());
        assertThat(advancementService.advance(byGold.roundId())).isTrue();
        Game finished = gameRepository.findById(byGold.gameId()).orElseThrow();
        assertThat(finished.getState()).isEqualTo(GameStates.FINISHED);
        assertThat(finished.getWinnerId()).isEqualTo(byGold.player0Id());
    }

    @Test
    void advance_twoThreads_exactlyOneSucceeds() throws Exception {
        Fixture f = seedUnadvanced(1, keep(20, 20), gold(10, 10), emptyBoard());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return advancementService.advance(f.roundId());
                }));
            }
            start.countDown();

            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }

            assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(1);
            assertThat(results.stream().filter(b -> !b)).hasSize(1);

            assertThat(roundRepository.findById(f.roundId()).orElseThrow().getAdvancedAt()).isNotNull();
            assertThat(gameRepository.findById(f.gameId()).orElseThrow().getCurrentRound()).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rounds WHERE game_id = ? AND round_number = 2",
                    Integer.class,
                    f.gameId())).isEqualTo(1);
            assertThat(shopCountForGame(f.gameId())).isEqualTo(6);
        } finally {
            pool.shutdownNow();
        }
    }

    private Fixture seedUnadvanced(int roundNumber, int[] keep, int[] gold, BoardLane board) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User u0 = userRepository.save(new User("a0_" + suffix, "a0_" + suffix + "@t.com", new byte[]{1}));
        User u1 = userRepository.save(new User("a1_" + suffix, "a1_" + suffix + "@t.com", new byte[]{1}));
        u0.setRating(1200);
        u1.setRating(1200);
        userRepository.save(u0);
        userRepository.save(u1);

        Game game = Game.create(u0.getId());
        game.setPlayer2Id(u1.getId());
        game.setState(GameStates.ROUND_RESULT);
        game.setCurrentRound(roundNumber);
        game = gameRepository.save(game);

        GamePlayer gp0 = new GamePlayer(game.getId(), u0.getId(), 0);
        gp0.setKeepHp(keep[0]);
        gamePlayerRepository.save(gp0);
        GamePlayer gp1 = new GamePlayer(game.getId(), u1.getId(), 1);
        gp1.setKeepHp(keep[1]);
        gamePlayerRepository.save(gp1);

        Round round = new Round(game.getId(), roundNumber, GameStates.ROUND_RESULT, Instant.now().plusSeconds(45));
        round.setFinishedAt(Instant.now());
        round.setOutcome("DRAW");
        round.setKeepDamage(Map.of("0", 0, "1", 0));
        // advanced_at null — TX3 input
        round = roundRepository.save(round);

        RoundPlan plan0 = new RoundPlan(round.getId(), u0.getId(), gold[0]);
        plan0.setLocked(true);
        plan0.setLockedAt(Instant.now());
        plan0.setBoardState(new HashMap<>(board.board()));
        plan0.setLaneUnits(new ArrayList<>(board.lane()));
        roundPlanRepository.save(plan0);

        RoundPlan plan1 = new RoundPlan(round.getId(), u1.getId(), gold[1]);
        plan1.setLocked(true);
        plan1.setLockedAt(Instant.now());
        plan1.setBoardState(new HashMap<>(board.board()));
        plan1.setLaneUnits(new ArrayList<>(board.lane()));
        roundPlanRepository.save(plan1);

        return new Fixture(game.getId(), round.getId(), u0.getId(), u1.getId());
    }

    private int shopCount(UUID roundId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_offers WHERE round_id = ?",
                Integer.class,
                roundId);
        return count == null ? 0 : count;
    }

    private int shopCountForGame(UUID gameId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM shop_offers so
                        JOIN rounds r ON r.id = so.round_id
                        WHERE r.game_id = ?
                        """,
                Integer.class,
                gameId);
        return count == null ? 0 : count;
    }

    private static int[] keep(int k0, int k1) {
        return new int[]{k0, k1};
    }

    private static int[] gold(int g0, int g1) {
        return new int[]{g0, g1};
    }

    private static BoardLane emptyBoard() {
        return new BoardLane(Map.of(), Arrays.asList(null, null, null, null, null));
    }

    private static BoardLane boardLane() {
        return new BoardLane(
                Map.of("0,0", Map.of("id", "u1", "type", "Squire", "level", 1)),
                java.util.Arrays.asList(Map.of("id", "lane-0"), null, null, null, null));
    }

    private record BoardLane(Map<String, Object> board, List<Object> lane) {
    }

    private record Fixture(UUID gameId, UUID roundId, UUID player0Id, UUID player1Id) {
    }
}

package com.kingdom.worker.job;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStateSnapshot;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.entity.User;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.GameStateSnapshotRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.domain.UnitInstance;
import com.kingdom.worker.service.ClaimService;
import com.kingdom.worker.service.ClaimedRound;
import com.kingdom.worker.service.ResolutionService;
import com.kingdom.worker.service.ResolveService;
import com.kingdom.worker.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoundResolutionJobIT extends AbstractPostgresIT {

    @Autowired
    private RoundResolutionJob job;
    @Autowired
    private ClaimService claimService;
    @Autowired
    private ResolutionService resolutionService;
    @Autowired
    private ResolveService resolveService;
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
    private GameStateSnapshotRepository snapshotRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void processLocked_claimEngineCommitAdvance_continuesToPreparation() {
        Fixture fixture = seedLockedRoundWithValidPlans();
        Map<String, Object> board0Before = copyBoard(fixture.roundId(), fixture.player0Id());
        Map<String, Object> board1Before = copyBoard(fixture.roundId(), fixture.player1Id());

        job.processLocked(fixture.roundId());
        assertPresentationThenAdvance(fixture);

        Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
        Game game = gameRepository.findById(fixture.gameId()).orElseThrow();

        // Completed round stays ROUND_RESULT forever; TX3 advances the game
        assertThat(round.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(round.getCombatSeed()).isNotNull();
        assertThat(round.getOutcome()).isNotNull();
        assertThat(round.getKeepDamage()).isNotNull().containsKeys("0", "1");
        assertThat(round.getFinishedAt()).isNotNull();
        assertThat(round.getAdvancedAt()).isNotNull();

        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        assertThat(game.getCurrentRound()).isEqualTo(2);

        Round next = roundRepository.findByGameIdAndRoundNumber(fixture.gameId(), 2).orElseThrow();
        assertThat(next.getState()).isEqualTo(GameStates.PREPARATION);
        assertThat(next.getCombatSeed()).isNull();

        // New plans: unlocked, gold+5; old plans untouched
        List<RoundPlan> nextPlans = roundPlanRepository.findByRoundId(next.getId());
        assertThat(nextPlans).hasSize(2);
        assertThat(nextPlans).allMatch(p -> !p.isLocked());
        assertThat(nextPlans).extracting(RoundPlan::getGold).containsOnly(15);

        Integer shopOffers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_offers WHERE round_id = ?",
                Integer.class,
                next.getId());
        assertThat(shopOffers).isEqualTo(6);

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_events WHERE game_id = ? AND round_number = ?",
                Integer.class,
                fixture.gameId(),
                1);
        assertThat(eventCount).isNotNull().isGreaterThan(0);

        Integer endSnapshots = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM game_state_snapshots
                        WHERE game_id = ? AND round_number = 1 AND is_round_start = false
                        """,
                Integer.class,
                fixture.gameId());
        assertThat(endSnapshots).isEqualTo(2);

        // Round-1 plans untouched (still locked formation from claim)
        RoundPlan plan0 = roundPlanRepository.findByRoundIdAndPlayerId(fixture.roundId(), fixture.player0Id())
                .orElseThrow();
        RoundPlan plan1 = roundPlanRepository.findByRoundIdAndPlayerId(fixture.roundId(), fixture.player1Id())
                .orElseThrow();
        assertThat(plan0.isLocked()).isTrue();
        assertThat(plan1.isLocked()).isTrue();
        assertThat(plan0.getBoardState()).isEqualTo(board0Before);
        assertThat(plan1.getBoardState()).isEqualTo(board1Before);

        // Path 3 finder should not see an already-advanced round
        List<UUID> needingAdvance = roundRepository.findIdsNeedingAdvance(
                GameStates.ROUND_RESULT, testClock.instant(), PageRequest.of(0, 50));
        assertThat(needingAdvance).doesNotContain(fixture.roundId());
    }

    @Test
    void advanceOnly_recoversUnadvancedRoundResult() {
        Fixture fixture = seedUnadvancedRoundResult();

        assertThat(roundRepository.findIdsNeedingAdvance(GameStates.ROUND_RESULT, testClock.instant(), PageRequest.of(0, 50)))
                .contains(fixture.roundId());

        job.advanceOnly(fixture.roundId());

        Round completed = roundRepository.findById(fixture.roundId()).orElseThrow();
        Game game = gameRepository.findById(fixture.gameId()).orElseThrow();
        assertThat(completed.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(completed.getAdvancedAt()).isNotNull();
        assertThat(game.getState()).isEqualTo(GameStates.PREPARATION);
        assertThat(game.getCurrentRound()).isEqualTo(2);
        assertThat(roundRepository.findByGameIdAndRoundNumber(fixture.gameId(), 2)).isPresent();
    }

    @Test
    void processLocked_whenEngineFails_leavesResolvingAndWritesNothing() {
        Fixture fixture = seedLockedRoundWithInvalidUnitType();

        job.processLocked(fixture.roundId());

        Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
        Game game = gameRepository.findById(fixture.gameId()).orElseThrow();

        assertThat(round.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(game.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(round.getCombatSeed()).isNotNull();
        assertThat(round.getOutcome()).isNull();
        assertThat(round.getKeepDamage()).isNull();
        assertThat(round.getFinishedAt()).isNull();
        assertThat(round.getAdvancedAt()).isNull();

        assertThat(eventCount(fixture.gameId(), 1)).isZero();
    }

    /**
     * Path 2: crash after TX1 leaves RESOLVING + seed; retryResolving completes TX2/TX3
     * without reclaiming or changing combat_seed.
     */
    @Test
    void retryResolving_recoversAfterCrashBetweenTx1AndTx2() {
        Fixture fixture = seedLockedRoundWithValidPlans();

        ClaimedRound claimed = claimService.claim(fixture.roundId()).orElseThrow();

        Round afterClaim = roundRepository.findById(fixture.roundId()).orElseThrow();
        Game gameAfterClaim = gameRepository.findById(fixture.gameId()).orElseThrow();
        assertThat(afterClaim.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(gameAfterClaim.getState()).isEqualTo(GameStates.RESOLVING);
        assertThat(afterClaim.getCombatSeed()).isEqualTo(claimed.combatSeed());
        assertThat(afterClaim.getOutcome()).isNull();
        assertThat(afterClaim.getAdvancedAt()).isNull();
        assertThat(eventCount(fixture.gameId(), 1)).isZero();

        Long seedAfterClaim = afterClaim.getCombatSeed();

        job.retryResolving(fixture.roundId());
        assertPresentationThenAdvance(fixture);

        Round afterRetry = roundRepository.findById(fixture.roundId()).orElseThrow();
        Game gameAfterRetry = gameRepository.findById(fixture.gameId()).orElseThrow();
        assertThat(afterRetry.getCombatSeed()).isEqualTo(seedAfterClaim);
        assertThat(afterRetry.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(afterRetry.getOutcome()).isNotNull();
        assertThat(afterRetry.getKeepDamage()).isNotNull().containsKeys("0", "1");
        assertThat(afterRetry.getFinishedAt()).isNotNull();
        assertThat(afterRetry.getAdvancedAt()).isNotNull();
        assertThat(eventCount(fixture.gameId(), 1)).isGreaterThan(0);
        assertThat(gameAfterRetry.getState()).isEqualTo(GameStates.PREPARATION);
        assertThat(gameAfterRetry.getCurrentRound()).isEqualTo(2);

        int eventsAfterFirst = eventCount(fixture.gameId(), 1);
        job.retryResolving(fixture.roundId()); // already ROUND_RESULT → no-op
        assertThat(eventCount(fixture.gameId(), 1)).isEqualTo(eventsAfterFirst);
        assertThat(roundRepository.findById(fixture.roundId()).orElseThrow().getCombatSeed())
                .isEqualTo(seedAfterClaim);
    }

    /**
     * After TX2: reload locked plans + combat_seed, re-run engine, match persisted
     * outcome / keepDamage / end-snapshot survivor counts.
     */
    @Test
    void afterTx2_replayingPlansAndSeed_matchesPersistedOutcomeAndSurvivors() {
        Fixture fixture = seedLockedRoundWithValidPlans();

        ClaimedRound claimed = claimService.claim(fixture.roundId()).orElseThrow();
        ResolutionResult first = resolutionService.resolve(
                fixture.roundId(), fixture.gameId(), claimed.combatSeed());
        assertThat(resolveService.commit(
                fixture.roundId(), fixture.gameId(), 1, first)).isTrue();

        Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
        assertThat(round.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(round.getAdvancedAt()).isNull();
        assertThat(round.getCombatSeed()).isEqualTo(claimed.combatSeed());

        ResolutionResult replay = resolutionService.resolve(
                fixture.roundId(), fixture.gameId(), round.getCombatSeed());

        assertThat(replay.getEvents()).isEqualTo(first.getEvents());
        assertThat(replay.getEndReason()).isEqualTo(round.getOutcome());
        assertThat(Map.of(
                "0", replay.getKeepDamageForPlayer(0),
                "1", replay.getKeepDamageForPlayer(1)))
                .isEqualTo(round.getKeepDamage());

        List<GameStateSnapshot> snaps = snapshotRepository
                .findByGameIdAndRoundNumberAndRoundStart(fixture.gameId(), 1, false);
        assertThat(snaps).hasSize(2);

        Map<UUID, Integer> seatByPlayer = Map.of(
                fixture.player0Id(), 0,
                fixture.player1Id(), 1);
        for (GameStateSnapshot snap : snaps) {
            int seat = seatByPlayer.get(snap.getPlayerId());
            long expectedSurvivors = replay.getFinalBoard().getAliveUnits().stream()
                    .map(UnitInstance::getPlayerId)
                    .filter(id -> id != null && id == seat)
                    .count();
            assertThat(snap.getBoard()).hasSize((int) expectedSurvivors);
            assertThat(snap.getKeepHp()).isEqualTo(
                    Math.max(0, 20 - replay.getKeepDamageForPlayer(seat)));
        }
    }

    private void assertPresentationThenAdvance(Fixture fixture) {
        Round pending = roundRepository.findById(fixture.roundId()).orElseThrow();
        assertThat(pending.getAdvancedAt()).isNull();
        assertThat(pending.getPresentationStartsAt()).isEqualTo(pending.getFinishedAt().plusSeconds(3));
        assertThat(pending.getPresentationEndsAt()).isEqualTo(pending.getCombatEndsAt().plusSeconds(4));
        assertThat(pending.getTickDurationMs()).isEqualTo(250);
        assertThat(gameRepository.findById(fixture.gameId()).orElseThrow().getState())
                .isEqualTo(GameStates.ROUND_RESULT);
        int count = eventCount(fixture.gameId(), 1);
        testClock.set(pending.getPresentationEndsAt().minusMillis(1));
        assertThat(roundRepository.findIdsNeedingAdvance(GameStates.ROUND_RESULT,
                testClock.instant(), PageRequest.of(0, 50))).doesNotContain(fixture.roundId());
        job.advanceOnly(fixture.roundId());
        assertThat(roundRepository.findById(fixture.roundId()).orElseThrow().getAdvancedAt()).isNull();
        // A fresh transaction after TX2 can recover solely from persisted data.
        testClock.set(pending.getPresentationEndsAt());
        assertThat(roundRepository.findIdsNeedingAdvance(GameStates.ROUND_RESULT,
                testClock.instant(), PageRequest.of(0, 50))).contains(fixture.roundId());
        job.advanceOnly(fixture.roundId());
        job.advanceOnly(fixture.roundId());
        assertThat(eventCount(fixture.gameId(), 1)).isEqualTo(count);
        assertThat(roundRepository.findById(fixture.roundId()).orElseThrow().getPresentationEndsAt())
                .isEqualTo(pending.getPresentationEndsAt());
        Round next = roundRepository.findByGameIdAndRoundNumber(fixture.gameId(), 2).orElseThrow();
        assertThat(next.getPlanningDeadline()).isEqualTo(testClock.instant().plusSeconds(45));
    }

    private int eventCount(UUID gameId, int roundNumber) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_events WHERE game_id = ? AND round_number = ?",
                Integer.class,
                gameId,
                roundNumber);
        return count == null ? 0 : count;
    }

    private Fixture seedLockedRoundWithValidPlans() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User p1 = userRepository.save(new User("u1_" + suffix, "u1_" + suffix + "@t.com", new byte[] {1}));
        User p2 = userRepository.save(new User("u2_" + suffix, "u2_" + suffix + "@t.com", new byte[] {1}));

        Game game = Game.create(p1.getId());
        game.setPlayer2Id(p2.getId());
        game.setState(GameStates.LOCKED);
        game.setCurrentRound(1);
        game = gameRepository.save(game);

        gamePlayerRepository.save(new GamePlayer(game.getId(), p1.getId(), 0));
        gamePlayerRepository.save(new GamePlayer(game.getId(), p2.getId(), 1));

        Round round = new Round(game.getId(), 1, GameStates.LOCKED, Instant.now().plusSeconds(45));
        round = roundRepository.save(round);

        // Same formation as ResolutionServiceTest — deterministic DRAW with events > 0
        RoundPlan plan0 = new RoundPlan(round.getId(), p1.getId(), 10);
        plan0.setLocked(true);
        plan0.setBoardState(new HashMap<>(Map.of("2,0", unit("unit_001", "Squire", 1))));
        roundPlanRepository.save(plan0);

        RoundPlan plan1 = new RoundPlan(round.getId(), p2.getId(), 10);
        plan1.setLocked(true);
        plan1.setBoardState(new HashMap<>(Map.of("1,2", unit("unit_002", "Squire", 1))));
        roundPlanRepository.save(plan1);

        return new Fixture(game.getId(), round.getId(), p1.getId(), p2.getId());
    }

    /** Post-TX2 checkpoint: ROUND_RESULT with advanced_at null (path 3 input). */
    private Fixture seedUnadvancedRoundResult() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User p1 = userRepository.save(new User("u1_" + suffix, "u1_" + suffix + "@t.com", new byte[] {1}));
        User p2 = userRepository.save(new User("u2_" + suffix, "u2_" + suffix + "@t.com", new byte[] {1}));

        Game game = Game.create(p1.getId());
        game.setPlayer2Id(p2.getId());
        game.setState(GameStates.ROUND_RESULT);
        game.setCurrentRound(1);
        game = gameRepository.save(game);

        GamePlayer gp0 = new GamePlayer(game.getId(), p1.getId(), 0);
        gp0.setKeepHp(20);
        gamePlayerRepository.save(gp0);
        GamePlayer gp1 = new GamePlayer(game.getId(), p2.getId(), 1);
        gp1.setKeepHp(20);
        gamePlayerRepository.save(gp1);

        Round round = new Round(game.getId(), 1, GameStates.ROUND_RESULT, Instant.now().plusSeconds(45));
        round.setFinishedAt(Instant.now());
        round.setOutcome("DRAW");
        round.setKeepDamage(Map.of("0", 0, "1", 0));
        // advanced_at stays null
        round = roundRepository.save(round);

        RoundPlan plan0 = new RoundPlan(round.getId(), p1.getId(), 10);
        plan0.setLocked(true);
        plan0.setBoardState(new HashMap<>(Map.of("2,0", unit("unit_001", "Squire", 1))));
        roundPlanRepository.save(plan0);

        RoundPlan plan1 = new RoundPlan(round.getId(), p2.getId(), 10);
        plan1.setLocked(true);
        plan1.setBoardState(new HashMap<>(Map.of("1,2", unit("unit_002", "Squire", 1))));
        roundPlanRepository.save(plan1);

        return new Fixture(game.getId(), round.getId(), p1.getId(), p2.getId());
    }

    private Fixture seedLockedRoundWithInvalidUnitType() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User p1 = userRepository.save(new User("u1_" + suffix, "u1_" + suffix + "@t.com", new byte[] {1}));
        User p2 = userRepository.save(new User("u2_" + suffix, "u2_" + suffix + "@t.com", new byte[] {1}));

        Game game = Game.create(p1.getId());
        game.setPlayer2Id(p2.getId());
        game.setState(GameStates.LOCKED);
        game.setCurrentRound(1);
        game = gameRepository.save(game);

        gamePlayerRepository.save(new GamePlayer(game.getId(), p1.getId(), 0));
        gamePlayerRepository.save(new GamePlayer(game.getId(), p2.getId(), 1));

        Round round = new Round(game.getId(), 1, GameStates.LOCKED, Instant.now().plusSeconds(45));
        round = roundRepository.save(round);

        RoundPlan plan0 = new RoundPlan(round.getId(), p1.getId(), 10);
        plan0.setLocked(true);
        plan0.setBoardState(new HashMap<>(Map.of("0,0", unit("bad", "Dragon", 1))));
        roundPlanRepository.save(plan0);

        RoundPlan plan1 = new RoundPlan(round.getId(), p2.getId(), 10);
        plan1.setLocked(true);
        plan1.setBoardState(new HashMap<>());
        roundPlanRepository.save(plan1);

        return new Fixture(game.getId(), round.getId(), p1.getId(), p2.getId());
    }

    private Map<String, Object> copyBoard(UUID roundId, UUID playerId) {
        RoundPlan plan = roundPlanRepository.findByRoundIdAndPlayerId(roundId, playerId).orElseThrow();
        return new HashMap<>(plan.getBoardState());
    }

    private static Map<String, Object> unit(String id, String type, int level) {
        Map<String, Object> map = new HashMap<>(3);
        map.put("id", id);
        map.put("type", type);
        map.put("level", level);
        return map;
    }

    private record Fixture(UUID gameId, UUID roundId, UUID player0Id, UUID player1Id) {
    }
}

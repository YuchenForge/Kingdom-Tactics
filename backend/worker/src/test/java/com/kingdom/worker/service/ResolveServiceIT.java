package com.kingdom.worker.service;

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
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.CombatEvent;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;
import com.kingdom.worker.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
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
 * TX2 idempotency: two workers finishing engine on RESOLVING — only one durable commit.
 */
class ResolveServiceIT extends AbstractPostgresIT {

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
    private JdbcTemplate jdbcTemplate;

    @Test
    void commit_secondWorkerNoOps_keepHpAndEventsUnchanged() {
        Fixture fixture = seedResolvingRound(keepHp(20, 15), lockedBoard());
        ResolutionResult result = drawResult(damage(2, 3));

        assertThat(resolveService.commit(fixture.roundId(), fixture.gameId(), 1, result)).isTrue();
        assertThat(resolveService.commit(fixture.roundId(), fixture.gameId(), 1, result)).isFalse();

        assertPostTx2Checkpoint(fixture, "DRAW", Map.of("0", 2, "1", 3), 18, 12, 2);
        assertRoundPlansUnchanged(fixture);
    }

    @Test
    void commit_twoThreads_exactlyOneSucceeds() throws Exception {
        Fixture fixture = seedResolvingRound(keepHp(20, 20), Map.of());
        ResolutionResult result = drawResult(damage(1, 1));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return resolveService.commit(fixture.roundId(), fixture.gameId(), 1, result);
                }));
            }
            start.countDown();

            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> f : futures) {
                results.add(f.get(15, TimeUnit.SECONDS));
            }

            assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(1);
            assertThat(results.stream().filter(b -> !b)).hasSize(1);

            assertThat(eventCount(fixture.gameId())).isEqualTo(2);
            List<GamePlayer> players = gamePlayerRepository.findByGameIdOrderBySeatAsc(fixture.gameId());
            assertThat(players.get(0).getKeepHp()).isEqualTo(19);
            assertThat(players.get(1).getKeepHp()).isEqualTo(19);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * /result history lives on round + end snapshots — not on live Keep HP.
     * Mutating game_players.keep_hp after TX2 must not erase outcome/keep_damage/snapshot HP.
     */
    @Test
    void commit_historicalFieldsSurviveLiveKeepHpMutation() {
        Fixture fixture = seedResolvingRound(keepHp(20, 15), lockedBoard());
        ResolutionResult result = drawResult(damage(2, 3));

        assertThat(resolveService.commit(fixture.roundId(), fixture.gameId(), 1, result)).isTrue();

        // Corrupt live Keep HP as if later rounds / cheats mutated it
        jdbcTemplate.update(
                "UPDATE game_players SET keep_hp = 1 WHERE game_id = ?",
                fixture.gameId());

        Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
        assertThat(round.getOutcome()).isEqualTo("DRAW");
        assertThat(round.getKeepDamage()).isEqualTo(Map.of("0", 2, "1", 3));
        assertThat(round.getFinishedAt()).isNotNull();
        assertThat(round.getAdvancedAt()).isNull();

        List<Integer> snapshotKeepHp = jdbcTemplate.queryForList(
                """
                        SELECT keep_hp FROM game_state_snapshots
                        WHERE game_id = ? AND round_number = 1 AND is_round_start = false
                        ORDER BY player_id
                        """,
                Integer.class,
                fixture.gameId());
        assertThat(snapshotKeepHp).containsExactlyInAnyOrder(18, 12);

        assertThat(eventCount(fixture.gameId())).isEqualTo(2);
        assertThat(eventSequences(fixture.gameId())).containsExactly(1, 2);

        List<GamePlayer> live = gamePlayerRepository.findByGameIdOrderBySeatAsc(fixture.gameId());
        assertThat(live.get(0).getKeepHp()).isEqualTo(1);
        assertThat(live.get(1).getKeepHp()).isEqualTo(1);
    }

    private void assertPostTx2Checkpoint(
            Fixture fixture,
            String outcome,
            Map<String, Integer> keepDamage,
            int keep0,
            int keep1,
            int expectedEvents) {
        Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
        Game game = gameRepository.findById(fixture.gameId()).orElseThrow();

        assertThat(round.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(game.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(round.getOutcome()).isEqualTo(outcome);
        assertThat(round.getKeepDamage()).isEqualTo(keepDamage);
        assertThat(round.getFinishedAt()).isNotNull();
        assertThat(round.getAdvancedAt()).isNull();

        List<GamePlayer> players = gamePlayerRepository.findByGameIdOrderBySeatAsc(fixture.gameId());
        assertThat(players.get(0).getKeepHp()).isEqualTo(keep0);
        assertThat(players.get(1).getKeepHp()).isEqualTo(keep1);

        assertThat(eventCount(fixture.gameId())).isEqualTo(expectedEvents);
        assertThat(eventSequences(fixture.gameId())).containsExactly(1, 2);
        assertThat(snapshotCount(fixture.gameId())).isEqualTo(2);
        assertThat(roundStartSnapshotCount(fixture.gameId())).isZero();
    }

    private void assertRoundPlansUnchanged(Fixture fixture) {
        List<RoundPlan> plans = roundPlanRepository.findByRoundId(fixture.roundId());
        assertThat(plans).hasSize(2);
        for (RoundPlan plan : plans) {
            assertThat(plan.isLocked()).isTrue();
            assertThat(plan.getGold()).isEqualTo(10);
            assertThat(plan.getBoardState()).isEqualTo(lockedBoard());
        }
    }

    private List<Integer> eventSequences(UUID gameId) {
        return jdbcTemplate.queryForList(
                """
                        SELECT sequence_num FROM game_events
                        WHERE game_id = ? AND round_number = 1
                        ORDER BY sequence_num
                        """,
                Integer.class,
                gameId);
    }

    private int eventCount(UUID gameId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_events WHERE game_id = ? AND round_number = 1",
                Integer.class,
                gameId);
        return count == null ? 0 : count;
    }

    private int snapshotCount(UUID gameId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM game_state_snapshots
                        WHERE game_id = ? AND round_number = 1 AND is_round_start = false
                        """,
                Integer.class,
                gameId);
        return count == null ? 0 : count;
    }

    private int roundStartSnapshotCount(UUID gameId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM game_state_snapshots
                        WHERE game_id = ? AND round_number = 1 AND is_round_start = true
                        """,
                Integer.class,
                gameId);
        return count == null ? 0 : count;
    }

    private Fixture seedResolvingRound(int[] keepHp, Map<String, Object> boardState) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User p1 = userRepository.save(new User("u1_" + suffix, "u1_" + suffix + "@t.com", new byte[] {1}));
        User p2 = userRepository.save(new User("u2_" + suffix, "u2_" + suffix + "@t.com", new byte[] {1}));

        Game game = Game.create(p1.getId());
        game.setPlayer2Id(p2.getId());
        game.setState(GameStates.RESOLVING);
        game.setCurrentRound(1);
        game = gameRepository.save(game);

        GamePlayer seat0 = new GamePlayer(game.getId(), p1.getId(), 0);
        seat0.setKeepHp(keepHp[0]);
        gamePlayerRepository.save(seat0);
        GamePlayer seat1 = new GamePlayer(game.getId(), p2.getId(), 1);
        seat1.setKeepHp(keepHp[1]);
        gamePlayerRepository.save(seat1);

        Round round = new Round(game.getId(), 1, GameStates.RESOLVING, Instant.now().plusSeconds(45));
        round.setCombatSeed(12345L);
        round = roundRepository.save(round);

        RoundPlan plan0 = new RoundPlan(round.getId(), p1.getId(), 10);
        plan0.setLocked(true);
        plan0.setBoardState(new HashMap<>(boardState));
        roundPlanRepository.save(plan0);

        RoundPlan plan1 = new RoundPlan(round.getId(), p2.getId(), 10);
        plan1.setLocked(true);
        plan1.setBoardState(new HashMap<>(boardState));
        roundPlanRepository.save(plan1);

        return new Fixture(game.getId(), round.getId());
    }

    private static Map<String, Object> lockedBoard() {
        Map<String, Object> unit = new HashMap<>(3);
        unit.put("id", "locked-1");
        unit.put("type", "Squire");
        unit.put("level", 1);
        return Map.of("1,1", unit);
    }

    private static int[] keepHp(int a, int b) {
        return new int[] {a, b};
    }

    private static int[] damage(int a, int b) {
        return new int[] {a, b};
    }

    private static ResolutionResult drawResult(int[] damage) {
        CombatBoard board = CombatBoard.merge(new Board(0), new Board(1));
        UnitInstance unit = new UnitInstance("u1", UnitDefinition.squire(), 0, 0);
        unit.setPlayerId(0);
        List<CombatEvent> events = List.of(
                CombatEvent.unitPlaced(0, unit),
                CombatEvent.combatEnded(0, "DRAW"));
        return new ResolutionResult(events, board, damage, 0, "DRAW", -1);
    }

    private record Fixture(UUID gameId, UUID roundId) {
    }
}

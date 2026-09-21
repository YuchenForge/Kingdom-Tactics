package com.kingdom.worker.job;

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
    void processLocked_claimEngineCommit_leavesRoundResultForPath3() {
        Fixture fixture = seedLockedRoundWithValidPlans();
        Map<String, Object> board0Before = copyBoard(fixture.roundId(), fixture.player0Id());
        Map<String, Object> board1Before = copyBoard(fixture.roundId(), fixture.player1Id());

        job.processLocked(fixture.roundId());

        Round round = roundRepository.findById(fixture.roundId()).orElseThrow();
        Game game = gameRepository.findById(fixture.gameId()).orElseThrow();

        assertThat(round.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(game.getState()).isEqualTo(GameStates.ROUND_RESULT);
        assertThat(round.getCombatSeed()).isNotNull();
        assertThat(round.getOutcome()).isNotNull();
        assertThat(round.getKeepDamage()).isNotNull().containsKeys("0", "1");
        assertThat(round.getFinishedAt()).isNotNull();
        assertThat(round.getAdvancedAt()).isNull();

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

        // plans untouched (still locked formation from claim)
        RoundPlan plan0 = roundPlanRepository.findByRoundIdAndPlayerId(fixture.roundId(), fixture.player0Id())
                .orElseThrow();
        RoundPlan plan1 = roundPlanRepository.findByRoundIdAndPlayerId(fixture.roundId(), fixture.player1Id())
                .orElseThrow();
        assertThat(plan0.isLocked()).isTrue();
        assertThat(plan1.isLocked()).isTrue();
        assertThat(plan0.getBoardState()).isEqualTo(board0Before);
        assertThat(plan1.getBoardState()).isEqualTo(board1Before);

        List<UUID> needingAdvance = roundRepository.findIdsNeedingAdvance(
                GameStates.ROUND_RESULT, PageRequest.of(0, 50));
        assertThat(needingAdvance).contains(fixture.roundId());
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

        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_events WHERE game_id = ? AND round_number = ?",
                Integer.class,
                fixture.gameId(),
                1);
        assertThat(eventCount).isZero();
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

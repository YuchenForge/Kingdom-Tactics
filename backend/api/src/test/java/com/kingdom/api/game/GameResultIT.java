package com.kingdom.api.game;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameEvent;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStateSnapshot;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.repository.GameEventRepository;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.GameStateSnapshotRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import com.kingdom.engine.domain.CombatOutcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 4 read APIs: events / round result / match result / state poll fields.
 * Seeds TX2/TX3 artifacts via repositories (api module does not depend on worker).
 */
class GameResultIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private GameRepository gameRepository;
    @Autowired
    private RoundRepository roundRepository;
    @Autowired
    private GamePlayerRepository gamePlayerRepository;
    @Autowired
    private RoundPlanRepository roundPlanRepository;
    @Autowired
    private GameEventRepository gameEventRepository;
    @Autowired
    private GameStateSnapshotRepository snapshotRepository;

    @Test
    void events_participantOnly_andPagination_andCompleteFlag() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "gr_evt");
        String charlieToken = TestAuthSupport.register(mockMvc, "charlie_evt", "charlie_evt@test.com");

        mockMvc.perform(get("/api/games/" + game.gameId() + "/events")
                        .param("round", "1")
                        .header("Authorization", "Bearer " + charlieToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_GAME_PARTICIPANT"));

        // No TX2 yet (RESOLVING, no events)
        markResolving(game);
        flush();

        mockMvc.perform(get("/api/games/" + game.gameId() + "/events")
                        .param("round", "1")
                        .param("afterSequence", "0")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundNumber", is(1)))
                .andExpect(jsonPath("$.events", hasSize(0)))
                .andExpect(jsonPath("$.nextAfterSequence", is(0)))
                .andExpect(jsonPath("$.hasMore", is(false)))
                .andExpect(jsonPath("$.complete", is(false)));

        // TX2: events + outcome
        commitTx2(game, damage(2, 3), keepAfter(18, 17), sampleEvents(UUID.fromString(game.gameId()), 1));
        flush();

        mockMvc.perform(get("/api/games/" + game.gameId() + "/events")
                        .param("round", "1")
                        .param("afterSequence", "0")
                        .param("limit", "2")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].sequenceNumber", is(1)))
                .andExpect(jsonPath("$.events[0].type", is("UNIT_PLACED")))
                .andExpect(jsonPath("$.events[0].data.level", is(1)))
                .andExpect(jsonPath("$.events[0].data.currentHp", is(10)))
                .andExpect(jsonPath("$.events[0].data.maxHp", is(10)))
                .andExpect(jsonPath("$.events[1].sequenceNumber", is(2)))
                .andExpect(jsonPath("$.nextAfterSequence", is(2)))
                .andExpect(jsonPath("$.hasMore", is(true)))
                .andExpect(jsonPath("$.complete", is(true)));

        mockMvc.perform(get("/api/games/" + game.gameId() + "/events")
                        .param("round", "1")
                        .param("afterSequence", "2")
                        .param("limit", "10")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.events[0].sequenceNumber", is(3)))
                .andExpect(jsonPath("$.nextAfterSequence", is(3)))
                .andExpect(jsonPath("$.hasMore", is(false)))
                .andExpect(jsonPath("$.complete", is(true)));
    }

    @Test
    void roundResult_409BeforeTx2_thenImmutableAfterAdvancement() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "gr_rr");
        String charlieToken = TestAuthSupport.register(mockMvc, "charlie_rr", "charlie_rr@test.com");

        mockMvc.perform(get("/api/games/" + game.gameId() + "/rounds/1/result")
                        .header("Authorization", "Bearer " + charlieToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_GAME_PARTICIPANT"));

        markResolving(game);
        flush();

        mockMvc.perform(get("/api/games/" + game.gameId() + "/rounds/1/result")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WRONG_GAME_STATE"));

        commitTx2(game, damage(3, 0), keepAfter(17, 20), sampleEvents(UUID.fromString(game.gameId()), 1));
        flush();

        mockMvc.perform(get("/api/games/" + game.gameId() + "/rounds/1/result")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roundNumber", is(1)))
                .andExpect(jsonPath("$.outcome", is(CombatOutcome.ENEMY_VICTORY.name())))
                .andExpect(jsonPath("$.keepDamage['0']", is(3)))
                .andExpect(jsonPath("$.keepDamage['1']", is(0)))
                .andExpect(jsonPath("$.keepHpAfter['0']", is(17)))
                .andExpect(jsonPath("$.keepHpAfter['1']", is(20)))
                .andExpect(jsonPath("$.endSnapshots['0'].keepHp", is(17)))
                .andExpect(jsonPath("$.endSnapshots['1'].keepHp", is(20)));

        // TX3 continue: round 2 exists; corrupt live Keep HP
        advanceToRound2(game);
        List<GamePlayer> seats = gamePlayerRepository.findByGameIdOrderBySeatAsc(UUID.fromString(game.gameId()));
        seats.get(0).setKeepHp(1);
        seats.get(1).setKeepHp(1);
        flush();

        mockMvc.perform(get("/api/games/" + game.gameId() + "/rounds/1/result")
                        .header("Authorization", "Bearer " + game.bobToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keepHpAfter['0']", is(17)))
                .andExpect(jsonPath("$.keepHpAfter['1']", is(20)))
                .andExpect(jsonPath("$.endSnapshots['0'].keepHp", is(17)));
    }

    @Test
    void matchResult_409UntilFinished_winnerAndDraw() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "gr_mr");
        String charlieToken = TestAuthSupport.register(mockMvc, "charlie_mr", "charlie_mr@test.com");

        mockMvc.perform(get("/api/games/" + game.gameId() + "/result")
                        .header("Authorization", "Bearer " + charlieToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("NOT_GAME_PARTICIPANT"));

        mockMvc.perform(get("/api/games/" + game.gameId() + "/result")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WRONG_GAME_STATE"));

        finishMatch(game, game.aliceId(), keepAfter(12, 0));
        flush();

        mockMvc.perform(get("/api/games/" + game.gameId() + "/result")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is(GameStates.FINISHED)))
                .andExpect(jsonPath("$.winnerId", is(game.aliceId().toString())))
                .andExpect(jsonPath("$.winnerUsername", is("gr_mr_a")))
                .andExpect(jsonPath("$.loserUsername", is("gr_mr_b")))
                .andExpect(jsonPath("$.finalKeepHp", hasSize(2)))
                .andExpect(jsonPath("$.finalKeepHp[0]", is(12)))
                .andExpect(jsonPath("$.finalKeepHp[1]", is(0)))
                .andExpect(jsonPath("$.finalRound", is(1)))
                .andExpect(jsonPath("$.finishedAt").exists());

        TestAuthSupport.JoinedGame drawGame = TestAuthSupport.joinTwoPlayers(mockMvc, "gr_dr");
        finishMatch(drawGame, null, keepAfter(0, 0));
        flush();

        mockMvc.perform(get("/api/games/" + drawGame.gameId() + "/result")
                        .header("Authorization", "Bearer " + drawGame.bobToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerId", nullValue()))
                .andExpect(jsonPath("$.winnerUsername", nullValue()))
                .andExpect(jsonPath("$.loserUsername", nullValue()))
                .andExpect(jsonPath("$.finalKeepHp", hasSize(2)))
                .andExpect(jsonPath("$.finalKeepHp[0]", is(0)))
                .andExpect(jsonPath("$.finalKeepHp[1]", is(0)));
    }

    @Test
    void state_latestResolvedRound_andPhaseStates_andPostDamageKeepHp() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "gr_st");

        mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is(GameStates.PREPARATION)))
                .andExpect(jsonPath("$.latestResolvedRound", nullValue()))
                .andExpect(jsonPath("$.yourKeepHp", is(20)));

        markResolving(game);
        flush();

        mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is(GameStates.RESOLVING)))
                .andExpect(jsonPath("$.latestResolvedRound", nullValue()));

        commitTx2(game, damage(2, 1), keepAfter(18, 19), sampleEvents(UUID.fromString(game.gameId()), 1));
        flush();

        mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is(GameStates.ROUND_RESULT)))
                .andExpect(jsonPath("$.latestResolvedRound", is(1)))
                .andExpect(jsonPath("$.yourKeepHp", is(18)))
                .andExpect(jsonPath("$.opponentKeepHp", is(19)));

        finishMatch(game, game.aliceId(), keepAfter(18, 0));
        flush();

        mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is(GameStates.FINISHED)))
                .andExpect(jsonPath("$.latestResolvedRound", is(1)))
                .andExpect(jsonPath("$.yourKeepHp", is(18)));
    }

    // --- seed helpers -------------------------------------------------------

    private void markResolving(TestAuthSupport.JoinedGame game) {
        UUID gameId = UUID.fromString(game.gameId());
        Game g = gameRepository.findById(gameId).orElseThrow();
        g.setState(GameStates.RESOLVING);
        Round round = roundRepository.findByGameIdAndRoundNumber(gameId, 1).orElseThrow();
        round.setState(GameStates.RESOLVING);
        round.setCombatSeed(42L);
        for (RoundPlan plan : roundPlanRepository.findByRoundId(round.getId())) {
            plan.setLocked(true);
        }
    }

    private void commitTx2(
            TestAuthSupport.JoinedGame game,
            Map<String, Integer> keepDamage,
            int[] keepAfter,
            List<GameEvent> events) {
        UUID gameId = UUID.fromString(game.gameId());
        Game g = gameRepository.findById(gameId).orElseThrow();
        Round round = roundRepository.findByGameIdAndRoundNumber(gameId, 1).orElseThrow();

        g.setState(GameStates.ROUND_RESULT);
        round.setState(GameStates.ROUND_RESULT);
        round.setOutcome(CombatOutcome.ENEMY_VICTORY.name());
        round.setKeepDamage(keepDamage);
        round.setFinishedAt(Instant.now());

        List<GamePlayer> seats = gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId);
        seats.get(0).setKeepHp(keepAfter[0]);
        seats.get(1).setKeepHp(keepAfter[1]);

        gameEventRepository.saveAll(events);

        snapshotRepository.save(new GameStateSnapshot(
                gameId, 1, false, game.aliceId(), keepAfter[0], 10,
                List.of(Map.of("id", "u1", "type", "Squire")), List.of(), null));
        snapshotRepository.save(new GameStateSnapshot(
                gameId, 1, false, game.bobId(), keepAfter[1], 10,
                List.of(), List.of(), null));
    }

    private void advanceToRound2(TestAuthSupport.JoinedGame game) {
        UUID gameId = UUID.fromString(game.gameId());
        Game g = gameRepository.findById(gameId).orElseThrow();
        Round round1 = roundRepository.findByGameIdAndRoundNumber(gameId, 1).orElseThrow();
        round1.setAdvancedAt(Instant.now());

        g.setState(GameStates.PREPARATION);
        g.setCurrentRound(2);

        Round round2 = roundRepository.save(new Round(
                gameId, 2, GameStates.PREPARATION, Instant.now().plusSeconds(45)));
        for (UUID playerId : List.of(game.aliceId(), game.bobId())) {
            RoundPlan plan = new RoundPlan(round2.getId(), playerId, 15);
            plan.setBoardState(new HashMap<>());
            plan.setLaneUnits(new ArrayList<>(Arrays.asList(null, null, null, null, null)));
            roundPlanRepository.save(plan);
        }
    }

    private void finishMatch(TestAuthSupport.JoinedGame game, UUID winnerId, int[] keepAfter) {
        UUID gameId = UUID.fromString(game.gameId());
        Game g = gameRepository.findById(gameId).orElseThrow();
        Round round = roundRepository.findByGameIdAndRoundNumber(gameId, g.getCurrentRound()).orElseThrow();

        if (round.getOutcome() == null) {
            List<GamePlayer> seatsForOutcome =
                    gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId);
            String outcome;
            if (winnerId == null) {
                outcome = CombatOutcome.DRAW.name();
            } else if (winnerId.equals(seatsForOutcome.get(0).getPlayerId())) {
                outcome = CombatOutcome.PLAYER_VICTORY.name(); // seat 0 won — not "requesting user"
            } else {
                outcome = CombatOutcome.ENEMY_VICTORY.name(); // seat 1 won
            }
            round.setOutcome(outcome);
            round.setKeepDamage(Map.of("0", 0, "1", 0));
            round.setFinishedAt(Instant.now());
            round.setState(GameStates.ROUND_RESULT);
        }
        round.setAdvancedAt(Instant.now());

        List<GamePlayer> seats = gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId);
        seats.get(0).setKeepHp(keepAfter[0]);
        seats.get(1).setKeepHp(keepAfter[1]);

        g.setState(GameStates.FINISHED);
        g.setWinnerId(winnerId);
        g.setFinishedAt(Instant.now());
    }

    private List<GameEvent> sampleEvents(UUID gameId, int roundNumber) {
        Map<String, Object> placed = new HashMap<>();
        placed.put("unitId", "u1");
        placed.put("unitType", "Squire");
        placed.put("x", 1);
        placed.put("y", 1);
        placed.put("playerId", 0);
        placed.put("level", 1);
        placed.put("currentHp", 10);
        placed.put("maxHp", 10);

        return List.of(
                new GameEvent(gameId, roundNumber, 1, "UNIT_PLACED", placed, 0),
                new GameEvent(gameId, roundNumber, 2, "UNIT_MOVED", Map.of("unitId", "u1", "x", 2, "y", 1), 1),
                new GameEvent(gameId, roundNumber, 3, "COMBAT_ENDED",
                        Map.of("reason", CombatOutcome.ENEMY_VICTORY.name()), 10));
    }

    private static Map<String, Integer> damage(int d0, int d1) {
        return Map.of("0", d0, "1", d1);
    }

    private static int[] keepAfter(int k0, int k1) {
        return new int[] {k0, k1};
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}

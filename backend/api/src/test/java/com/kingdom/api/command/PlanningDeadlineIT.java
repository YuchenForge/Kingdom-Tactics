package com.kingdom.api.command;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end deadline enforcement: mutate deadline in DB.
 * Production uses Clock.systemUTC(); services compare against that clock.
 * 
 * Overrides the inherited test @Transactional: deadline auto-lock commits in
 * REQUIRES_NEW, which cannot see uncommitted setup rows from a wrapping test TX.
 * Each HTTP call therefore commits on its own (unique usernames avoid collisions).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PlanningDeadlineIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private RoundRepository roundRepository;

    @Autowired
    private RoundPlanRepository roundPlanRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Expired command auto-locks in REQUIRES_NEW. Assert LOCKED in a fresh read TX
     * before any GET, because GET itself can finalize the deadline.
     */
    @Test
    void afterDeadline_rejectedCommand_persistsAutoLock_thenStateShowsBothLocked() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String aliceToken = TestAuthSupport.register(
                mockMvc, "dl_alice_" + suffix, "dl_alice_" + suffix + "@test.com");
        String bobToken = TestAuthSupport.register(
                mockMvc, "dl_bob_" + suffix, "dl_bob_" + suffix + "@test.com");

        String gameId = TestAuthSupport.createGame(mockMvc, aliceToken);
        mockMvc.perform(post("/api/games/" + gameId + "/join")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("PREPARATION")))
                .andExpect(jsonPath("$.planningDeadline", notNullValue()));

        UUID gameUuid = UUID.fromString(gameId);
        Round round = roundRepository.findByGameIdAndRoundNumber(gameUuid, 1).orElseThrow();
        assertThat(round.getPlanningDeadline()).isAfter(Instant.now());

        mockMvc.perform(post("/api/games/" + gameId + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

        round.setPlanningDeadline(Instant.now().minusSeconds(1));
        roundRepository.saveAndFlush(round);

        mockMvc.perform(post("/api/games/" + gameId + "/rounds/1/relocate")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":"any","to":{"type":"BOARD","x":0,"y":0}}
                                """))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error", is("DEADLINE_PASSED")));

        TransactionTemplate freshRead = new TransactionTemplate(transactionManager);
        freshRead.executeWithoutResult(status -> {
            Game game = gameRepository.findById(gameUuid).orElseThrow();
            assertThat(game.getState()).isEqualTo(GameStates.LOCKED);

            Round lockedRound = roundRepository.findByGameIdAndRoundNumber(gameUuid, 1).orElseThrow();
            assertThat(lockedRound.getState()).isEqualTo(GameStates.LOCKED);

            List<RoundPlan> plans = roundPlanRepository.findByRoundId(lockedRound.getId());
            assertThat(plans).hasSize(2);
            assertThat(plans).allMatch(RoundPlan::isLocked);
        });

        mockMvc.perform(get("/api/games/" + gameId + "/state")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("LOCKED")))
                .andExpect(jsonPath("$.isLocked", is(true)))
                .andExpect(jsonPath("$.opponentIsLocked", is(true)));

        mockMvc.perform(get("/api/games/" + gameId + "/state")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("LOCKED")))
                .andExpect(jsonPath("$.isLocked", is(true)))
                .andExpect(jsonPath("$.opponentIsLocked", is(true)));
    }

    /**
     * Deadline auto-lock commits in REQUIRES_NEW after the plan was loaded unlocked.
     * An existing-key retry must still return isLocked=true from committed state.
     */
    @Test
    void pastDeadline_idempotentRetry_returnsIsLockedTrue() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String aliceToken = TestAuthSupport.register(
                mockMvc, "dl_idem_a_" + suffix, "dl_idem_a_" + suffix + "@test.com");
        String bobToken = TestAuthSupport.register(
                mockMvc, "dl_idem_b_" + suffix, "dl_idem_b_" + suffix + "@test.com");

        String gameId = TestAuthSupport.createGame(mockMvc, aliceToken);
        mockMvc.perform(post("/api/games/" + gameId + "/join")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        String idempotencyKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/games/" + gameId + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.isLocked", is(false)));

        UUID gameUuid = UUID.fromString(gameId);
        Round round = roundRepository.findByGameIdAndRoundNumber(gameUuid, 1).orElseThrow();
        round.setPlanningDeadline(Instant.now().minusSeconds(1));
        roundRepository.saveAndFlush(round);

        mockMvc.perform(post("/api/games/" + gameId + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.isLocked", is(true)));
    }
}

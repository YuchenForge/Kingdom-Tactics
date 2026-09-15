package com.kingdom.api.command;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end deadline enforcement: mutate deadline in DB 
 * Production uses Clock.systemUTC(); services compare against that clock.
 */
class PlanningDeadlineIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GameRepository gameRepository;

    @Autowired
    private RoundRepository roundRepository;

    @Test
    void afterDeadline_relocateRejected_getStateShowsBothLocked() throws Exception {
        String aliceToken = TestAuthSupport.register(mockMvc, "dl_alice", "dl_alice@test.com");
        String bobToken = TestAuthSupport.register(mockMvc, "dl_bob", "dl_bob@test.com");

        String gameId = TestAuthSupport.createGame(mockMvc, aliceToken);
        mockMvc.perform(post("/api/games/" + gameId + "/join")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("PREPARATION")))
                .andExpect(jsonPath("$.planningDeadline", notNullValue()));

        UUID gameUuid = UUID.fromString(gameId);
        Round round = roundRepository.findByGameIdAndRoundNumber(gameUuid, 1).orElseThrow();
        Instant deadlineAtJoin = round.getPlanningDeadline();
        assertThat(deadlineAtJoin).isAfter(Instant.now());

        // Before deadline: buy still works.
        mockMvc.perform(post("/api/games/" + gameId + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

        // Expire planning without waiting 45s.
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

        Game game = gameRepository.findById(gameUuid).orElseThrow();
        assertThat(game.getState()).isEqualTo(GameStates.LOCKED);
        Round lockedRound = roundRepository.findByGameIdAndRoundNumber(gameUuid, 1).orElseThrow();
        assertThat(lockedRound.getState()).isEqualTo(GameStates.LOCKED);

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
}

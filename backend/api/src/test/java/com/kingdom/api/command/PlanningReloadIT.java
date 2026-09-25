package com.kingdom.api.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.mapper.PlanningStateMapper;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.service.ShopService;
import com.kingdom.api.support.AbstractPostgresIT;
import com.kingdom.api.support.TestAuthSupport;
import com.kingdom.engine.domain.UnitTypeResolver;
import com.kingdom.engine.planning.PlanningShop;
import com.kingdom.engine.planning.PlanningState;
import com.kingdom.engine.planning.PlanningUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * Reload-safe planning UI: GET /state must expose type/level/display stats for
 * placed and auto-merged units without client history or balance tables.
 */
class PlanningReloadIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RoundRepository roundRepository;

    @Autowired
    private RoundPlanRepository roundPlanRepository;

    @Autowired
    private ShopService shopService;

    @Test
    void getState_afterPurchaseMergeAndReload_exposesUnitStatsFromServer() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "reload");
        UUID roundId = roundRepository
                .findByGameIdAndRoundNumber(UUID.fromString(game.gameId()), 1)
                .orElseThrow()
                .getId();

        // Force a known Squire offer so a purchase can complete a 3× L1 → L2 merge.
        shopService.persistOffers(roundId, game.aliceId(), List.of("Squire", "Mage", "Ranger"));

        RoundPlan plan = roundPlanRepository
                .findByRoundIdAndPlayerId(roundId, game.aliceId())
                .orElseThrow();
        PlanningUnit[] lane = new PlanningUnit[PlanningState.LANE_SIZE];
        lane[0] = new PlanningUnit("seed-a", "Squire", 1);
        lane[1] = new PlanningUnit("seed-b", "Squire", 1);
        PlanningState seeded = new PlanningState(
                10,
                false,
                PlanningShop.of("Squire", "Mage", "Ranger"),
                lane,
                new PlanningUnit[com.kingdom.engine.domain.Board.WIDTH][com.kingdom.engine.domain.Board.HEIGHT],
                1);
        PlanningStateMapper.applyToPlan(plan, seeded);
        roundPlanRepository.save(plan);

        MvcResult buy = mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andReturn();

        JsonNode buyBody = objectMapper.readTree(buy.getResponse().getContentAsString());
        String mergedId = null;
        for (JsonNode slot : buyBody.path("lane")) {
            if (!slot.path("unitId").isNull()
                    && "Squire".equals(slot.path("unitType").asText())
                    && slot.path("level").asInt() == 2) {
                mergedId = slot.path("unitId").asText();
                break;
            }
        }
        assertThat(mergedId).isNotBlank();

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/relocate")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":"%s","to":{"type":"BOARD","x":0,"y":0}}
                                """.formatted(mergedId)))
                .andExpect(status().isOk());

        // Second purchase → place a level-1 unit on the board for "placed" coverage.
        shopService.persistOffers(roundId, game.aliceId(), List.of("Ranger", "Mage", "Knight"));
        MvcResult buy2 = mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andReturn();
        String placedId = objectMapper.readTree(buy2.getResponse().getContentAsString())
                .path("lane").get(0).path("unitId").asText();
        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/relocate")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":"%s","to":{"type":"BOARD","x":1,"y":0}}
                                """.formatted(placedId)))
                .andExpect(status().isOk());

        // Leave a knight on the lane via a third buy.
        shopService.persistOffers(roundId, game.aliceId(), List.of("Knight", "Mage", "Healer"));
        MvcResult buy3 = mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andReturn();
        String laneId = objectMapper.readTree(buy3.getResponse().getContentAsString())
                .path("lane").get(0).path("unitId").asText();

        MvcResult state = mockMvc.perform(get("/api/games/" + game.gameId() + "/state")
                        .header("Authorization", "Bearer " + game.aliceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yourBoard[0][0]", is(mergedId)))
                .andExpect(jsonPath("$.yourBoard[0][1]", is(placedId)))
                .andExpect(jsonPath("$.yourLane[0].unitId", is(laneId)))
                .andExpect(jsonPath("$.yourUnits." + mergedId + ".level", is(2)))
                .andExpect(jsonPath("$.yourUnits." + placedId + ".unitType", is("Ranger")))
                .andExpect(jsonPath("$.yourUnits." + laneId + ".unitType", is("Knight")))
                .andExpect(jsonPath("$.shop", org.hamcrest.Matchers.hasSize(3)))
                .andReturn();

        JsonNode units = objectMapper.readTree(state.getResponse().getContentAsString()).path("yourUnits");
        var squire = UnitTypeResolver.resolve("Squire");
        var ranger = UnitTypeResolver.resolve("Ranger");
        var knight = UnitTypeResolver.resolve("Knight");
        assertUnitView(units.get(mergedId), "Squire", 2,
                squire.getMaxHp(2), squire.getAttack(2), squire.getRange(),
                squire.getSpecialAbility(), squire.getHealAmount(2));
        assertUnitView(units.get(placedId), "Ranger", 1,
                ranger.getMaxHp(1), ranger.getAttack(1), ranger.getRange(),
                ranger.getSpecialAbility(), ranger.getHealAmount(1));
        assertUnitView(units.get(laneId), "Knight", 1,
                knight.getMaxHp(1), knight.getAttack(1), knight.getRange(),
                knight.getSpecialAbility(), knight.getHealAmount(1));
    }

    @Test
    void relocateCommand_includesUnitsLookupWithDisplayStats() throws Exception {
        TestAuthSupport.JoinedGame game = TestAuthSupport.joinTwoPlayers(mockMvc, "cmdunits");

        MvcResult buy = mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/buy")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopSlot\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units", notNullValue()))
                .andReturn();

        JsonNode buyBody = objectMapper.readTree(buy.getResponse().getContentAsString());
        String unitId = buyBody.path("lane").get(0).path("unitId").asText();
        String unitType = buyBody.path("lane").get(0).path("unitType").asText();
        var def = UnitTypeResolver.resolve(unitType);

        assertThat(buyBody.path("units").path(unitId).path("level").asInt()).isEqualTo(1);
        assertThat(buyBody.path("units").path(unitId).path("maxHp").asInt())
                .isEqualTo(def.getMaxHp(1));
        assertThat(buyBody.path("shop").get(0).path("unitType").isNull()).isTrue();

        mockMvc.perform(post("/api/games/" + game.gameId() + "/rounds/1/relocate")
                        .header("Authorization", "Bearer " + game.aliceToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitId":"%s","to":{"type":"BOARD","x":0,"y":0}}
                                """.formatted(unitId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.board[0][0]", is(unitId)))
                .andExpect(jsonPath("$.lane[0].unitId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.units." + unitId + ".unitType", is(unitType)))
                .andExpect(jsonPath("$.units." + unitId + ".maxHp", is(def.getMaxHp(1))))
                .andExpect(jsonPath("$.units." + unitId + ".attack", is(def.getAttack(1))));
    }

    private static void assertUnitView(
            JsonNode node,
            String unitType,
            int level,
            int maxHp,
            int attack,
            int range,
            String specialAbility,
            int healAmount) {
        assertThat(node.path("unitType").asText()).isEqualTo(unitType);
        assertThat(node.path("level").asInt()).isEqualTo(level);
        assertThat(node.path("maxHp").asInt()).isEqualTo(maxHp);
        assertThat(node.path("attack").asInt()).isEqualTo(attack);
        assertThat(node.path("range").asInt()).isEqualTo(range);
        assertThat(node.path("specialAbility").asText()).isEqualTo(specialAbility);
        assertThat(node.path("healAmount").asInt()).isEqualTo(healAmount);
    }
}

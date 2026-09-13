package com.kingdom.api.mapper;

import com.kingdom.api.dto.GameResponse;
import com.kingdom.api.dto.GameStateResponse;
import com.kingdom.api.dto.ShopSlotDto;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GameMapperTest {

    @Test
    void toGameResponseMapsPlayersAndDeadline() {
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        Game game = Game.create(playerId);
        ReflectionTestUtils.setField(game, "id", gameId);
        ReflectionTestUtils.setField(game, "createdAt", Instant.parse("2024-01-15T14:23:45Z"));
        ReflectionTestUtils.setField(game, "startedAt", Instant.parse("2024-01-15T14:23:45Z"));

        User user = new User("alice", "alice@test.com", "hash".getBytes());
        ReflectionTestUtils.setField(user, "id", playerId);

        GamePlayer seat = new GamePlayer(gameId, playerId, 0);
        Instant deadline = Instant.parse("2024-01-15T14:24:30Z");

        GameResponse response = GameMapper.toGameResponse(
                game,
                List.of(seat),
                Map.of(playerId, user),
                Map.of(playerId, 10),
                deadline);

        assertThat(response.gameId()).isEqualTo(gameId);
        assertThat(response.state()).isEqualTo(GameStates.WAITING_FOR_PLAYERS);
        assertThat(response.players()).hasSize(1);
        assertThat(response.players().get(0).seat()).isEqualTo(0);
        assertThat(response.players().get(0).gold()).isEqualTo(10);
        assertThat(response.players().get(0).username()).isEqualTo("alice");
        assertThat(response.planningDeadline()).isEqualTo(deadline);
    }

    @Test
    void toGameStateResponseIsViewerProjectionWithoutOpponentBoard() {
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        Game game = Game.create(playerId);
        ReflectionTestUtils.setField(game, "id", gameId);
        game.setState(GameStates.PREPARATION);
        game.setCurrentRound(1);

        Instant deadline = Instant.parse("2024-01-15T14:24:30Z");
        List<ShopSlotDto> shop = List.of(
                new ShopSlotDto(0, "Squire", 1),
                new ShopSlotDto(1, "Mage", 3),
                new ShopSlotDto(2, "Ranger", 2));
        GameStateResponse response = GameMapper.toGameStateResponse(
                game, 0, 10, deadline, false, true, shop);

        assertThat(response.gameId()).isEqualTo(gameId);
        assertThat(response.yourSeat()).isEqualTo(0);
        assertThat(response.yourGold()).isEqualTo(10);
        assertThat(response.yourBoard()).hasSize(4);
        assertThat(response.yourBoard().get(0)).hasSize(4);
        assertThat(response.yourLane()).hasSize(5);
        assertThat(response.shop()).isEqualTo(shop);
        assertThat(response.opponentUnitCount()).isEqualTo(0);
        assertThat(response.opponentIsLocked()).isTrue();
        assertThat(response.isLocked()).isFalse();
    }
}

package com.kingdom.api.mapper;

import com.kingdom.api.dto.GameResponse;
import com.kingdom.api.dto.GameStateResponse;
import com.kingdom.api.dto.LaneSlotDto;
import com.kingdom.api.dto.PlayerSummary;
import com.kingdom.api.dto.ShopSlotDto;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.User;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GameMapper {

    private GameMapper() {
    }

    public static GameResponse toGameResponse(
            Game game,
            List<GamePlayer> seats,
            Map<UUID, User> usersById,
            Map<UUID, Integer> goldByPlayerId,
            Instant planningDeadline) {
        List<PlayerSummary> players = seats.stream()
                .map(gp -> {
                    User user = usersById.get(gp.getPlayerId());
                    int gold = goldByPlayerId.getOrDefault(gp.getPlayerId(), 0);
                    return new PlayerSummary(
                            user.getId(),
                            user.getUsername(),
                            gp.getKeepHp(),
                            gold,
                            gp.getSeat());
                })
                .toList();

        return new GameResponse(
                game.getId(),
                game.getState(),
                game.getCurrentRound(),
                players,
                planningDeadline,
                game.getCreatedAt(),
                game.getStartedAt());
    }

    public static GameStateResponse toGameStateResponse(
            Game game,
            int yourSeat,
            int yourKeepHp,
            int yourGold,
            int opponentKeepHp,
            int opponentUnitCount,
            List<List<String>> yourBoard,
            List<LaneSlotDto> yourLane,
            List<ShopSlotDto> shop,
            Instant planningDeadline,
            boolean isLocked,
            boolean opponentIsLocked) {
        return new GameStateResponse(
                game.getId(),
                game.getState(),
                game.getCurrentRound(),
                yourSeat,
                yourKeepHp,
                yourGold,
                opponentKeepHp,
                opponentUnitCount,
                yourBoard,
                yourLane,
                shop,
                planningDeadline,
                isLocked,
                opponentIsLocked);
    }
}

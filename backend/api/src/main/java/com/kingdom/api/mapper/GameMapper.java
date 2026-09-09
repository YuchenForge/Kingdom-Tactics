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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GameMapper {

    private static final int DEFAULT_KEEP_HP = 20;

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
                            DEFAULT_KEEP_HP,
                            gold,
                            gp.getSeat(),
                            gp.isReady());
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
            int yourGold,
            Instant planningDeadline,
            boolean isLocked,
            boolean opponentIsLocked) {
        return new GameStateResponse(
                game.getId(),
                game.getState(),
                game.getCurrentRound(),
                yourSeat,
                DEFAULT_KEEP_HP,
                yourGold,
                DEFAULT_KEEP_HP,
                0,
                emptyBoard4x4(),
                emptyLane(),
                emptyShop(),
                planningDeadline,
                isLocked,
                opponentIsLocked);
    }

    /** Phase 2 stub: empty board until placement exists. */
    static List<List<String>> emptyBoard4x4() {
        List<List<String>> board = new ArrayList<>(4);
        for (int y = 0; y < 4; y++) {
            board.add(Arrays.asList(null, null, null, null));
        }
        return board;
    }

    /** Phase 2 stub: five empty lane slots. */
    static List<LaneSlotDto> emptyLane() {
        List<LaneSlotDto> lane = new ArrayList<>(5);
        for (int slot = 0; slot < 5; slot++) {
            lane.add(new LaneSlotDto(slot, null, null, null));
        }
        return lane;
    }

    /** Phase 2 stub: no shop offers yet. */
    static List<ShopSlotDto> emptyShop() {
        return Collections.emptyList();
    }
}

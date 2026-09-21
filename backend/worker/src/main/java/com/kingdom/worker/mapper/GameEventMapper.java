package com.kingdom.worker.mapper;

import com.kingdom.api.entity.GameEvent;
import com.kingdom.engine.domain.CombatEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure mapper: in-memory CombatEvents → durable GameEvent rows (1-based sequence).
 */
public final class GameEventMapper {

    private GameEventMapper() {
    }

    public static List<GameEvent> toEntities(UUID gameId, int roundNumber, List<CombatEvent> events) {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(events, "events");
        List<GameEvent> rows = new ArrayList<>(events.size());

        for (int i = 0; i < events.size(); i++) {
            CombatEvent event = events.get(i);
            rows.add(new GameEvent(
                    gameId,
                    roundNumber,
                    i + 1,
                    event.getType().name(),
                    event.getData(),
                    event.getTick()));
        }
        return rows;
    }
}

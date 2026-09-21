package com.kingdom.worker.mapper;

import com.kingdom.api.entity.GameEvent;
import com.kingdom.engine.domain.CombatEvent;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GameEventMapperTest {

    @Test
    void toEntities_assignsOneBasedSequenceAndCopiesPayload() {
        UUID gameId = UUID.randomUUID();
        UnitInstance unit = new UnitInstance("u1", UnitDefinition.squire(), 1, 3, 2);
        unit.setPlayerId(0);
        CombatEvent placed = CombatEvent.unitPlaced(0, unit);
        CombatEvent ended = CombatEvent.combatEnded(5, "DRAW");

        List<GameEvent> rows = GameEventMapper.toEntities(gameId, 3, List.of(placed, ended));

        assertThat(rows).hasSize(2);

        GameEvent first = rows.get(0);
        assertThat(first.getGameId()).isEqualTo(gameId);
        assertThat(first.getRoundNumber()).isEqualTo(3);
        assertThat(first.getSequenceNum()).isEqualTo(1);
        assertThat(first.getEventType()).isEqualTo("UNIT_PLACED");
        assertThat(first.getTick()).isZero();
        assertThat(first.getData())
                .containsEntry("unitId", "u1")
                .containsEntry("level", 2)
                .containsEntry("currentHp", 12)
                .containsEntry("maxHp", 12);

        GameEvent second = rows.get(1);
        assertThat(second.getSequenceNum()).isEqualTo(2);
        assertThat(second.getEventType()).isEqualTo("COMBAT_ENDED");
        assertThat(second.getTick()).isEqualTo(5);
        assertThat(second.getData()).containsEntry("reason", "DRAW");
    }

    @Test
    void toEntities_emptyListYieldsEmpty() {
        assertThat(GameEventMapper.toEntities(UUID.randomUUID(), 1, List.of())).isEmpty();
    }
}

package com.kingdom.worker.mapper;

import com.kingdom.api.entity.GameStateSnapshot;
import com.kingdom.engine.domain.Board;
import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.UnitDefinition;
import com.kingdom.engine.domain.UnitInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EndSnapshotMapperTest {

    @Test
    void toBoardJson_filtersBySeatAndUsesGlobalCoords() {
        Board p0 = new Board(0);
        p0.addUnit(new UnitInstance("a", UnitDefinition.squire(), 2, 0, 1));
        Board p1 = new Board(1);
        p1.addUnit(new UnitInstance("b", UnitDefinition.mage(), 1, 2, 2));
        CombatBoard combat = CombatBoard.merge(p0, p1);

        List<Object> seat0 = EndSnapshotMapper.toBoardJson(combat, 0);
        List<Object> seat1 = EndSnapshotMapper.toBoardJson(combat, 1);

        assertThat(seat0).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> u0 = (Map<String, Object>) seat0.get(0);
        assertThat(u0).containsEntry("id", "a")
                .containsEntry("type", "Squire")
                .containsEntry("level", 1)
                .containsEntry("x", 1)
                .containsEntry("y", 3)
                .containsEntry("currentHp", 8)
                .containsEntry("maxHp", 8);

        assertThat(seat1).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> u1 = (Map<String, Object>) seat1.get(0);
        assertThat(u1).containsEntry("id", "b")
                .containsEntry("type", "Mage")
                .containsEntry("level", 2)
                .containsEntry("x", 1)
                .containsEntry("y", 6)
                .containsEntry("currentHp", 16)
                .containsEntry("maxHp", 16);
    }

    @Test
    void toEndSnapshot_setsRoundEndFlagsLaneEmptyShopNullAndPlanGold() {
        CombatBoard empty = CombatBoard.merge(new Board(0), new Board(1));
        UUID gameId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();

        GameStateSnapshot snap = EndSnapshotMapper.toEndSnapshot(
                gameId, 2, playerId, 17, 12, empty, 0);

        assertThat(snap.getGameId()).isEqualTo(gameId);
        assertThat(snap.getRoundNumber()).isEqualTo(2);
        assertThat(snap.isRoundStart()).isFalse();
        assertThat(snap.getPlayerId()).isEqualTo(playerId);
        assertThat(snap.getKeepHp()).isEqualTo(17);
        assertThat(snap.getGold()).isEqualTo(12);
        assertThat(snap.getBoard()).isEmpty();
        assertThat(snap.getLane()).isEmpty();
        assertThat(snap.getShop()).isNull();
    }
}

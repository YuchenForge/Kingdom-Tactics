package com.kingdom.engine.cli;

import java.util.ArrayList;
import java.util.List;

import com.kingdom.engine.domain.CombatBoard;
import com.kingdom.engine.domain.CombatEvent;
import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.domain.UnitInstance;

/** Maps domain to CLI JSON DTOs. */
public final class CombatResultMapper {

    private CombatResultMapper() {
    }

    public static CombatResultDto toDto(long seed, ResolutionResult result) {
        CombatResultDto dto = new CombatResultDto();
        dto.seed = seed;
        dto.finalTick = result.getFinalTick();
        dto.endReason = result.getEndReason();
        dto.winnerPlayerId = result.getWinnerPlayerId();
        dto.keepDamage = new int[] {
            result.getKeepDamageForPlayer(0),
            result.getKeepDamageForPlayer(1)
        };
        dto.events = mapEvents(result.getEvents());
        dto.survivingUnits = mapSurvivors(result.getFinalBoard());
        return dto;
    }

    private static List<CombatEventDto> mapEvents(List<CombatEvent> events) {
        List<CombatEventDto> mapped = new ArrayList<>(events.size());
        for (CombatEvent event : events) {
            CombatEventDto dto = new CombatEventDto();
            dto.type = event.getType().name();
            dto.tick = event.getTick();
            dto.data = event.getData();
            mapped.add(dto);
        }
        return mapped;
    }

    private static List<SurvivingUnitDto> mapSurvivors(CombatBoard board) {
        List<SurvivingUnitDto> survivors = new ArrayList<>();
        for (UnitInstance unit : board.getAliveUnits()) {
            SurvivingUnitDto dto = new SurvivingUnitDto();
            dto.id = unit.getId();
            dto.type = unit.getType();
            dto.playerId = unit.getPlayerId();
            dto.level = unit.getLevel();
            dto.x = unit.getX();
            dto.y = unit.getY();
            dto.currentHp = unit.getCurrentHp();
            dto.maxHp = unit.getMaxHp();
            survivors.add(dto);
        }
        return survivors;
    }
}

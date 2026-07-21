package com.kingdom.engine.cli;

import java.util.List;

/** JSON-serializable combat resolution written to stdout by the CLI. */
public class CombatResultDto {
    public long seed;
    public List<CombatEventDto> events;
    public int finalTick;
    public String endReason;
    public int winnerPlayerId;
    /** Keep damage dealt to each player: index 0 = player 0, index 1 = player 1. */
    public int[] keepDamage;
    public List<SurvivingUnitDto> survivingUnits;
}

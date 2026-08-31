package com.kingdom.engine.cli;

/** JSON-serializable surviving unit snapshot at combat end. */
public class SurvivingUnitDto {
    public String id;
    public String type;
    public int playerId;
    public int level;
    public int x;
    public int y;
    public int currentHp;
    public int maxHp;
}

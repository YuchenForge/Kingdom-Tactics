package com.kingdom.engine.cli;

import java.util.Map;

/** JSON-serializable combat event for CLI output. */
public class CombatEventDto {
    public String type;
    public int tick;
    public Map<String, Object> data;
}

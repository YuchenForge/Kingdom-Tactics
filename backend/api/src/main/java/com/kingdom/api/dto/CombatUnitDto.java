package com.kingdom.api.dto;

/** A deployed unit at its initial global combat position; never includes lane units. */
public record CombatUnitDto(String id, String type, int level, int seat, int x, int y) {}

package com.kingdom.engine.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-size holding lane for units purchased from the shop before placement.
 * Units in the lane take no combat actions.
 */
public class HoldingLane {
    public static final int SIZE = 5;

    private final int playerId;
    private final List<String> slots;

    public HoldingLane(int playerId) {
        if (playerId != 0 && playerId != 1) {
            throw new IllegalArgumentException("playerId must be 0 or 1: " + playerId);
        }
        this.playerId = playerId;
        this.slots = new ArrayList<>(Collections.nCopies(SIZE, null));
    }

    public HoldingLane(HoldingLane other) {
        this.playerId = other.playerId;
        this.slots = new ArrayList<>(other.slots);
    }

    public int getPlayerId() {
        return playerId;
    }

    public boolean isFull() {
        return slots.stream().allMatch(Objects::nonNull);
    }

    public boolean isEmpty() {
        return slots.stream().allMatch(Objects::isNull);
    }

    public int occupiedCount() {
        return (int) slots.stream().filter(Objects::nonNull).count();
    }

    public boolean contains(String unitId) {
        return slots.contains(unitId);
    }

    /**
     * Add a unit to the first empty slot. Returns the slot index, or -1 if full.
     */
    public int addUnit(String unitId) {
        Objects.requireNonNull(unitId);
        if (contains(unitId)) {
            throw new IllegalArgumentException("Unit already in lane: " + unitId);
        }
        for (int i = 0; i < SIZE; i++) {
            if (slots.get(i) == null) {
                slots.set(i, unitId);
                return i;
            }
        }
        return -1;
    }

    public void removeUnit(String unitId) {
        for (int i = 0; i < SIZE; i++) {
            if (unitId.equals(slots.get(i))) {
                slots.set(i, null);
                return;
            }
        }
        throw new IllegalArgumentException("Unit not in lane: " + unitId);
    }

    public String getUnitAtSlot(int slot) {
        validateSlot(slot);
        return slots.get(slot);
    }

    public List<String> getUnits() {
        return slots.stream().filter(Objects::nonNull).toList();
    }

    public List<String> getSlots() {
        return Collections.unmodifiableList(slots);
    }

    private static void validateSlot(int slot) {
        if (slot < 0 || slot >= SIZE) {
            throw new IllegalArgumentException("Invalid lane slot: " + slot);
        }
    }
}

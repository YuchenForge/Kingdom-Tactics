package com.kingdom.engine.planning;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Three shop slots: unit type string or null (sold / empty).
 * Immutable — buy/refresh return a new instance.
 */
public final class PlanningShop {
    public static final int SLOT_COUNT = 3;

    private final String[] slots;

    public PlanningShop(List<String> offers) {
        Objects.requireNonNull(offers, "offers");
        if (offers.size() != SLOT_COUNT) {
            throw new IllegalArgumentException(
                "shop must have exactly " + SLOT_COUNT + " slots, got " + offers.size());
        }
        this.slots = new String[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            this.slots[i] = offers.get(i);
        }
    }

    private PlanningShop(String[] slots) {
        this.slots = slots;
    }

    public static PlanningShop of(String offer0, String offer1, String offer2) {
        return new PlanningShop(List.of(offer0, offer1, offer2));
    }

    /** Empty shop: all three slots sold / unavailable. */
    public static PlanningShop empty() {
        return new PlanningShop(new String[SLOT_COUNT]);
    }

    public String get(int slot) {
        validateSlot(slot);
        return slots[slot];
    }

    public boolean isEmpty(int slot) {
        return get(slot) == null;
    }

    /** Mark a slot as sold (null). */
    public PlanningShop withSlotSold(int slot) {
        validateSlot(slot);
        String[] copy = slots.clone();
        copy[slot] = null;
        return new PlanningShop(copy);
    }

    /** Replace all three offers (e.g. after refresh). */
    public PlanningShop withOffers(List<String> offers) {
        return new PlanningShop(offers);
    }

    public List<String> getSlots() {
        return Collections.unmodifiableList(Arrays.asList(slots.clone()));
    }

    private static void validateSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException(
                "shop slot must be 0.." + (SLOT_COUNT - 1) + ": " + slot);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlanningShop other)) {
            return false;
        }
        return Arrays.equals(slots, other.slots);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(slots);
    }

    @Override
    public String toString() {
        return "PlanningShop" + Arrays.toString(slots);
    }
}

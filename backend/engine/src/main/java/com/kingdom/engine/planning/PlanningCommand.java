package com.kingdom.engine.planning;

import java.util.List;
import java.util.Objects;

import com.kingdom.engine.domain.HoldingLane;

/**
 * Sealed command payloads for pure planning apply:
 * Buy, Sell, Refresh, Relocate, Lock.
 */
public sealed interface PlanningCommand
        permits PlanningCommand.Buy,
                PlanningCommand.Sell,
                PlanningCommand.Refresh,
                PlanningCommand.Relocate,
                PlanningCommand.Lock {

    /**
     * Where a unit should end up after Relocate.
     * Covers lane↔lane, lane→board, board→board, board→lane.
     */
    sealed interface Destination permits Destination.Board, Destination.Lane {
        /** Board cell at planning coords board[x][y] */
        record Board(int x, int y) implements Destination {}

        /** Holding-lane slot index 0..HoldingLane.SIZE-1 */
        record Lane(int slot) implements Destination {
            public Lane {
                if (slot < 0 || slot >= HoldingLane.SIZE) {
                    throw new IllegalArgumentException(
                        "lane slot must be 0.." + (HoldingLane.SIZE - 1) + ": " + slot);
                }
            }
        }
    }

    /** Buy the unit type in shopSlot (0–2); creates a Level-1 unit on the lane. */
    record Buy(int shopSlot) implements PlanningCommand {
        public Buy {
            if (shopSlot < 0 || shopSlot >= PlanningShop.SLOT_COUNT) {
                throw new IllegalArgumentException(
                    "shopSlot must be 0.." + (PlanningShop.SLOT_COUNT - 1) + ": " + shopSlot);
            }
        }
    }

    /** Sell a unit from lane or board; refund base_cost × level. */
    record Sell(String unitId) implements PlanningCommand {
        public Sell {
            Objects.requireNonNull(unitId, "unitId");
        }
    }

    /**
     * Replace all shop offers. Caller supplies the 3 types (RNG stays outside this layer).
     */
    record Refresh(List<String> newOffers) implements PlanningCommand {
        public Refresh {
            Objects.requireNonNull(newOffers, "newOffers");
            newOffers = List.copyOf(newOffers);
            if (newOffers.size() != PlanningShop.SLOT_COUNT) {
                throw new IllegalArgumentException(
                    "newOffers must have exactly " + PlanningShop.SLOT_COUNT
                        + " entries, got " + newOffers.size());
            }
            for (String offer : newOffers) {
                Objects.requireNonNull(offer, "offer type");
            }
        }
    }

    /** Move a unit to a board cell or lane slot (any of the four path combinations). */
    record Relocate(String unitId, Destination to) implements PlanningCommand {
        public Relocate {
            Objects.requireNonNull(unitId, "unitId");
            Objects.requireNonNull(to, "to");
        }

        public static Relocate toBoard(String unitId, int x, int y) {
            return new Relocate(unitId, new Destination.Board(x, y));
        }

        public static Relocate toLane(String unitId, int slot) {
            return new Relocate(unitId, new Destination.Lane(slot));
        }
    }

    /** Lock the board; no further planning commands allowed. */
    record Lock() implements PlanningCommand {}
}

package com.kingdom.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RelocateRequest(
        @NotBlank String unitId,
        @NotNull @Valid Destination to) {

    public enum DestinationType {
        BOARD,
        LANE
    }

    public record Destination(
            @NotNull DestinationType type,
            Integer x,
            Integer y,
            Integer slot
    ) {
        
        @AssertTrue(message = "must provide x and y for BOARD, or slot for LANE")
        public boolean isCoordinateShapeValid() {
            if (type == null) {
                return true;
            }
            return switch (type) {
                case BOARD -> x != null && y != null;
                case LANE -> slot != null;
            };
        }
    }
}

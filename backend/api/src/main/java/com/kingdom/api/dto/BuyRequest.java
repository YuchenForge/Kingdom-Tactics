package com.kingdom.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record BuyRequest(@Min(0) @Max(2) int shopSlot) {
}

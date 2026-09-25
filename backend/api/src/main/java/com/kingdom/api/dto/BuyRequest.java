package com.kingdom.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record BuyRequest(@NotNull @Min(0) @Max(2) Integer shopSlot) {
}

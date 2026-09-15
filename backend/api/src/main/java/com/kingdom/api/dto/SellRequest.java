package com.kingdom.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SellRequest(@NotBlank String unitId) {
}

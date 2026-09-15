package com.kingdom.api.service;

import com.kingdom.api.dto.CommandResponse;
import com.kingdom.api.dto.RelocateRequest;

import java.util.UUID;

public interface CommandService {

    CommandResponse buy(UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey, int shopSlot);

    CommandResponse refresh(UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey);

    CommandResponse sell(UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey, String unitId);

    CommandResponse relocate(
            UUID gameId,
            int roundNumber,
            UUID userId,
            UUID idempotencyKey,
            String unitId,
            RelocateRequest.Destination to);

    CommandResponse lock(UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey);
}

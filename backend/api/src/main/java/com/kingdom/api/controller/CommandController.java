package com.kingdom.api.controller;

import com.kingdom.api.dto.BuyRequest;
import com.kingdom.api.dto.CommandResponse;
import com.kingdom.api.dto.RelocateRequest;
import com.kingdom.api.dto.SellRequest;
import com.kingdom.api.service.CommandService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/games")
public class CommandController {

    private final CommandService commandService;

    public CommandController(CommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping("/{gameId}/rounds/{roundNumber}/buy")
    public CommandResponse buy(
            @PathVariable("gameId") UUID gameId,
            @PathVariable("roundNumber") int roundNumber,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody BuyRequest body,
            @AuthenticationPrincipal UUID userId) {
        return commandService.buy(gameId, roundNumber, userId, idempotencyKey, body.shopSlot());
    }

    @PostMapping("/{gameId}/rounds/{roundNumber}/sell")
    public CommandResponse sell(
            @PathVariable("gameId") UUID gameId,
            @PathVariable("roundNumber") int roundNumber,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody SellRequest body,
            @AuthenticationPrincipal UUID userId) {
        return commandService.sell(gameId, roundNumber, userId, idempotencyKey, body.unitId());
    }

    @PostMapping("/{gameId}/rounds/{roundNumber}/refresh")
    public CommandResponse refresh(
            @PathVariable("gameId") UUID gameId,
            @PathVariable("roundNumber") int roundNumber,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal UUID userId) {
        return commandService.refresh(gameId, roundNumber, userId, idempotencyKey);
    }

    @PostMapping("/{gameId}/rounds/{roundNumber}/relocate")
    public CommandResponse relocate(
            @PathVariable("gameId") UUID gameId,
            @PathVariable("roundNumber") int roundNumber,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody RelocateRequest body,
            @AuthenticationPrincipal UUID userId) {
        return commandService.relocate(
                gameId, roundNumber, userId, idempotencyKey, body.unitId(), body.to());
    }

    @PostMapping("/{gameId}/rounds/{roundNumber}/lock")
    public CommandResponse lock(
            @PathVariable("gameId") UUID gameId,
            @PathVariable("roundNumber") int roundNumber,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @AuthenticationPrincipal UUID userId) {
        return commandService.lock(gameId, roundNumber, userId, idempotencyKey);
    }
}

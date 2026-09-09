package com.kingdom.api.controller;

import com.kingdom.api.dto.GameResponse;
import com.kingdom.api.dto.GameStateResponse;
import com.kingdom.api.service.GameService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GameResponse create(@AuthenticationPrincipal UUID userId) {
        return gameService.createGame(userId);
    }

    @PostMapping("/{gameId}/join")
    public GameResponse join(
            @PathVariable("gameId") UUID gameId,
            @AuthenticationPrincipal UUID userId) {
        // do NOT call assertParticipant — joiner is not a participant yet
        return gameService.joinGame(gameId, userId);
    }

    @GetMapping("/{gameId}")
    public GameResponse get(
            @PathVariable("gameId") UUID gameId,
            @AuthenticationPrincipal UUID userId) {
        return gameService.getGame(gameId, userId);
    }

    @GetMapping("/{gameId}/state")
    public GameStateResponse state(
            @PathVariable("gameId") UUID gameId,
            @AuthenticationPrincipal UUID userId) {
        return gameService.getState(gameId, userId);
    }
}

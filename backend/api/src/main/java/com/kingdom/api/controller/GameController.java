package com.kingdom.api.controller;

import com.kingdom.api.dto.EventsResponse;
import com.kingdom.api.dto.GameResponse;
import com.kingdom.api.dto.GameStateResponse;
import com.kingdom.api.dto.MatchResultResponse;
import com.kingdom.api.dto.RoundResultResponse;
import com.kingdom.api.service.GameResultService;
import com.kingdom.api.service.GameService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;
    private final GameResultService gameResultService;

    public GameController(GameService gameService, GameResultService gameResultService) {
        this.gameService = gameService;
        this.gameResultService = gameResultService;
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

    @GetMapping("/{gameId}/events")
    public EventsResponse events(
            @PathVariable("gameId") UUID gameId,
            @RequestParam("round") int round,
            @RequestParam(name = "afterSequence", defaultValue = "0") int afterSequence,
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            @AuthenticationPrincipal UUID userId) {
        return gameResultService.getEvents(gameId, userId, round, afterSequence, limit);
    }

    @GetMapping("/{gameId}/rounds/{roundNumber}/result")
    public RoundResultResponse roundResult(
            @PathVariable("gameId") UUID gameId,
            @PathVariable("roundNumber") int roundNumber,
            @AuthenticationPrincipal UUID userId) {
        return gameResultService.getRoundResult(gameId, userId, roundNumber);
    }

    @GetMapping("/{gameId}/result")
    public MatchResultResponse matchResult(
            @PathVariable("gameId") UUID gameId,
            @AuthenticationPrincipal UUID userId) {
        return gameResultService.getMatchResult(gameId, userId);
    }
}

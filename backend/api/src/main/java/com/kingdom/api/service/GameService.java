package com.kingdom.api.service;

import com.kingdom.api.dto.GameResponse;
import com.kingdom.api.dto.GameStateResponse;
import com.kingdom.api.dto.LaneSlotDto;
import com.kingdom.api.dto.PlayerSummary;
import com.kingdom.api.dto.ShopSlotDto;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.entity.User;
import com.kingdom.api.exception.AlreadyInGameException;
import com.kingdom.api.exception.GameFullException;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.exception.GameNotReadyException;
import com.kingdom.api.exception.NotGameParticipantException;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
public class GameService {

    static final int DEFAULT_KEEP_HP = 20;
    static final int STARTING_GOLD = 10;
    static final int PLANNING_SECONDS = 45;

    private final GameRepository gameRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final RoundRepository roundRepository;
    private final RoundPlanRepository roundPlanRepository;
    private final UserRepository userRepository;

    public GameService(
            GameRepository gameRepository,
            GamePlayerRepository gamePlayerRepository,
            RoundRepository roundRepository,
            RoundPlanRepository roundPlanRepository,
            UserRepository userRepository) {
        this.gameRepository = gameRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.roundRepository = roundRepository;
        this.roundPlanRepository = roundPlanRepository;
        this.userRepository = userRepository;
    }

    // Call at the start of every game-scoped read/action once controllers exist.
    // Skip for POST /games (no game yet) and POST /games/{id}/join (caller not a participant yet).
    public void assertParticipant(UUID gameId, UUID userId) {
        if (!gameRepository.existsById(gameId)) {
            throw new GameNotFoundException(gameId);
        }
        if (!gamePlayerRepository.existsByGameIdAndPlayerId(gameId, userId)) {
            throw new NotGameParticipantException(gameId, userId);
        }
    }

    // One transaction: game row + seat 0 commit together. Rounds/plans are created on join.
    @Transactional
    public GameResponse createGame(UUID creatorId) {
        Game game = gameRepository.save(Game.create(creatorId));
        gamePlayerRepository.save(new GamePlayer(game.getId(), creatorId, 0));
        return toGameResponse(game);
    }

    // One transaction: seat 1 + game → PREPARATION + round 1 + two plans.
    @Transactional
    public GameResponse joinGame(UUID gameId, UUID joinerId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (gamePlayerRepository.existsByGameIdAndPlayerId(gameId, joinerId)
                || joinerId.equals(game.getPlayer1Id())) {
            throw new AlreadyInGameException(gameId, joinerId);
        }

        if (!GameStates.WAITING_FOR_PLAYERS.equals(game.getState())
                || game.getPlayer2Id() != null
                || gamePlayerRepository.countByGameId(gameId) >= 2) {
            throw new GameFullException(gameId);
        }

        try {
            gamePlayerRepository.saveAndFlush(new GamePlayer(gameId, joinerId, 1));
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(game_id, seat) race — another joiner took seat 1
            throw new GameFullException(gameId);
        }

        game.setPlayer2Id(joinerId);
        game.setState(GameStates.PREPARATION);
        game.setCurrentRound(1);

        Instant now = Instant.now();
        Round round = roundRepository.save(new Round(
                gameId,
                1,
                GameStates.PREPARATION,
                now.plusSeconds(PLANNING_SECONDS)));

        createInitialPlan(round.getId(), game.getPlayer1Id());
        createInitialPlan(round.getId(), joinerId);

        return toGameResponse(game);
    }

    @Transactional(readOnly = true)
    public GameResponse getGame(UUID gameId, UUID userId) {
        assertParticipant(gameId, userId);
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        return toGameResponse(game);
    }

    @Transactional(readOnly = true)
    public GameStateResponse getState(UUID gameId, UUID userId) {
        assertParticipant(gameId, userId);
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (GameStates.WAITING_FOR_PLAYERS.equals(game.getState())) {
            throw new GameNotReadyException(gameId);
        }

        GamePlayer you = gamePlayerRepository.findByGameIdAndPlayerId(gameId, userId)
                .orElseThrow(() -> new NotGameParticipantException(gameId, userId));
        Round round = roundRepository.findByGameIdAndRoundNumber(gameId, game.getCurrentRound())
                .orElseThrow(() -> new IllegalStateException("Missing round for game " + gameId));
        RoundPlan yourPlan = roundPlanRepository.findByRoundIdAndPlayerId(round.getId(), userId)
                .orElseThrow(() -> new IllegalStateException("Missing plan for player " + userId));

        UUID opponentId = you.getSeat() == 0 ? game.getPlayer2Id() : game.getPlayer1Id();
        RoundPlan opponentPlan = roundPlanRepository
                .findByRoundIdAndPlayerId(round.getId(), opponentId)
                .orElseThrow(() -> new IllegalStateException("Missing plan for opponent " + opponentId));

        // Opponent board/lane contents never appear here — only lock flag + keepHp stub.
        return new GameStateResponse(
                game.getId(),
                game.getState(),
                game.getCurrentRound(),
                you.getSeat(),
                DEFAULT_KEEP_HP,
                yourPlan.getGold(),
                DEFAULT_KEEP_HP,
                0,
                emptyBoard4x4(),
                emptyLane(),
                emptyShop(),
                round.getPlanningDeadline(),
                yourPlan.isLocked(),
                opponentPlan.isLocked());
    }

    private void createInitialPlan(UUID roundId, UUID playerId) {
        RoundPlan plan = new RoundPlan(roundId, playerId, STARTING_GOLD);
        plan.setBoardState(new HashMap<>());
        plan.setLaneUnits(new ArrayList<>(Arrays.asList(null, null, null, null, null)));
        roundPlanRepository.save(plan);
    }

    private GameResponse toGameResponse(Game game) {
        List<GamePlayer> seats = gamePlayerRepository.findByGameIdOrderBySeatAsc(game.getId());
        List<PlayerSummary> players = seats.stream()
                .map(gp -> {
                    User u = userRepository.findById(gp.getPlayerId()).orElseThrow();
                    int gold = resolveGold(game, gp);
                    return new PlayerSummary(
                            u.getId(),
                            u.getUsername(),
                            DEFAULT_KEEP_HP,
                            gold,
                            gp.getSeat(),
                            gp.isReady());
                })
                .toList();

        Instant deadline = null;
        if (game.getCurrentRound() > 0) {
            deadline = roundRepository
                    .findByGameIdAndRoundNumber(game.getId(), game.getCurrentRound())
                    .map(Round::getPlanningDeadline)
                    .orElse(null);
        }

        return new GameResponse(
                game.getId(),
                game.getState(),
                game.getCurrentRound(),
                players,
                deadline,
                game.getCreatedAt(),
                game.getStartedAt());
    }

    // 10 while WAITING (no plan yet); plan gold once PREPARATION / round exists
    private int resolveGold(Game game, GamePlayer seat) {
        if (game.getCurrentRound() <= 0) {
            return STARTING_GOLD;
        }
        return roundRepository
                .findByGameIdAndRoundNumber(game.getId(), game.getCurrentRound())
                .flatMap(round -> roundPlanRepository.findByRoundIdAndPlayerId(round.getId(), seat.getPlayerId()))
                .map(RoundPlan::getGold)
                .orElse(STARTING_GOLD);
    }

    private static List<List<String>> emptyBoard4x4() {
        List<List<String>> board = new ArrayList<>(4);
        for (int y = 0; y < 4; y++) {
            board.add(Arrays.asList(null, null, null, null));
        }
        return board;
    }

    private static List<LaneSlotDto> emptyLane() {
        List<LaneSlotDto> lane = new ArrayList<>(5);
        for (int slot = 0; slot < 5; slot++) {
            lane.add(new LaneSlotDto(slot, null, null, null));
        }
        return lane;
    }

    private static List<ShopSlotDto> emptyShop() {
        return Collections.emptyList();
    }
}

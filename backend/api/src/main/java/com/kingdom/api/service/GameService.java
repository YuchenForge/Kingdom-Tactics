package com.kingdom.api.service;

import com.kingdom.api.dto.GameResponse;
import com.kingdom.api.dto.GameStateResponse;
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
import com.kingdom.api.mapper.GameMapper;
import com.kingdom.api.mapper.PlanningStateMapper;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.engine.planning.PlanningShop;
import com.kingdom.engine.planning.PlanningState;
import com.kingdom.engine.planning.PlanningUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    static final int STARTING_GOLD = 10;
    static final int PLANNING_SECONDS = 45;

    private final GameRepository gameRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final RoundRepository roundRepository;
    private final RoundPlanRepository roundPlanRepository;
    private final UserRepository userRepository;
    private final ShopService shopService;

    public GameService(
            GameRepository gameRepository,
            GamePlayerRepository gamePlayerRepository,
            RoundRepository roundRepository,
            RoundPlanRepository roundPlanRepository,
            UserRepository userRepository,
            ShopService shopService) {
        this.gameRepository = gameRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.roundRepository = roundRepository;
        this.roundPlanRepository = roundPlanRepository;
        this.userRepository = userRepository;
        this.shopService = shopService;
    }

    // Call at the start of every game-scoped read/action.
    // Skip for POST /games (no game yet) and POST /games/{id}/join (caller not a participant yet).
    public void assertParticipant(UUID gameId, UUID userId) {
        mdcGame(gameId);
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
        mdcGame(game.getId());
        gamePlayerRepository.save(new GamePlayer(game.getId(), creatorId, 0));
        log.info("Game created state={}", game.getState());
        return toGameResponse(game);
    }

    // One transaction: seat 1 + game → PREPARATION + round 1 + two plans + shops.
    @Transactional
    public GameResponse joinGame(UUID gameId, UUID joinerId) {
        mdcGame(gameId);
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (gamePlayerRepository.existsByGameIdAndPlayerId(gameId, joinerId)
                || joinerId.equals(game.getPlayer1Id())) {
            log.info("Join rejected: already in game");
            throw new AlreadyInGameException(gameId, joinerId);
        }

        if (!GameStates.WAITING_FOR_PLAYERS.equals(game.getState())
                || game.getPlayer2Id() != null
                || gamePlayerRepository.countByGameId(gameId) >= 2) {
            log.info("Join rejected: game full or not joinable state={}", game.getState());
            throw new GameFullException(gameId);
        }

        try {
            gamePlayerRepository.saveAndFlush(new GamePlayer(gameId, joinerId, 1));
        } catch (DataIntegrityViolationException e) {
            // UNIQUE(game_id, seat) race — another joiner took seat 1
            log.info("Join rejected: seat race lost");
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
        shopService.createShopsForRound(round, gameId, game.getPlayer1Id(), joinerId);

        log.info("Player joined; state={} round={}", game.getState(), game.getCurrentRound());
        return toGameResponse(game);
    }

    @Transactional(readOnly = true)
    public GameResponse getGame(UUID gameId, UUID userId) {
        mdcGame(gameId);
        assertParticipant(gameId, userId);
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        return toGameResponse(game);
    }

    @Transactional(readOnly = true)
    public GameStateResponse getState(UUID gameId, UUID userId) {
        mdcGame(gameId);
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
        GamePlayer opponent = gamePlayerRepository.findByGameIdAndPlayerId(gameId, opponentId)
                .orElseThrow(() -> new IllegalStateException("Missing seat for opponent " + opponentId));

        // Viewer shop only — never load opponent offers.
        PlanningShop yourShop = shopService.loadShop(round.getId(), userId);
        PlanningState yourState = PlanningStateMapper.toPlanningState(
                yourPlan, yourShop, round.getRoundNumber());
        PlanningState opponentState = PlanningStateMapper.toPlanningState(
                opponentPlan, PlanningShop.empty(), round.getRoundNumber());

        return GameMapper.toGameStateResponse(
                game,
                you.getSeat(),
                you.getKeepHp(),
                yourPlan.getGold(),
                opponent.getKeepHp(),
                countUnits(opponentState),
                PlanningStateMapper.toBoardIdGrid(yourState.getBoard()),
                PlanningStateMapper.toLaneDtos(yourState.getLane()),
                PlanningStateMapper.toShopDtos(yourShop),
                round.getPlanningDeadline(),
                yourPlan.isLocked(),
                opponentPlan.isLocked());
    }

    private static int countUnits(PlanningState state) {
        int count = state.boardUnitCount();
        for (PlanningUnit unit : state.getLane()) {
            if (unit != null) {
                count++;
            }
        }
        return count;
    }

    private static void mdcGame(UUID gameId) {
        if (gameId != null) {
            MDC.put("gameId", gameId.toString());
        }
    }

    private void createInitialPlan(UUID roundId, UUID playerId) {
        RoundPlan plan = new RoundPlan(roundId, playerId, STARTING_GOLD);
        plan.setBoardState(new HashMap<>());
        plan.setLaneUnits(new ArrayList<>(Arrays.asList(null, null, null, null, null)));
        roundPlanRepository.save(plan);
    }

    private GameResponse toGameResponse(Game game) {
        List<GamePlayer> seats = gamePlayerRepository.findByGameIdOrderBySeatAsc(game.getId());
        Map<UUID, User> usersById = new HashMap<>();
        Map<UUID, Integer> goldByPlayerId = new HashMap<>();
        for (GamePlayer seat : seats) {
            usersById.put(seat.getPlayerId(), userRepository.findById(seat.getPlayerId()).orElseThrow());
            goldByPlayerId.put(seat.getPlayerId(), resolveGold(game, seat));
        }

        Instant deadline = null;
        if (game.getCurrentRound() > 0) {
            deadline = roundRepository
                    .findByGameIdAndRoundNumber(game.getId(), game.getCurrentRound())
                    .map(Round::getPlanningDeadline)
                    .orElse(null);
        }

        return GameMapper.toGameResponse(game, seats, usersById, goldByPlayerId, deadline);
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
}

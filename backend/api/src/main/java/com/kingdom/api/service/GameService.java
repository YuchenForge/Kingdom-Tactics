package com.kingdom.api.service;

import com.kingdom.api.dto.GameResponse;
import com.kingdom.api.dto.CombatUnitDto;
import com.kingdom.engine.domain.Coordinates;
import java.util.ArrayList;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    static final int STARTING_GOLD = 10;
    public static final int PLANNING_SECONDS = 45;

    private final GameRepository gameRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final RoundRepository roundRepository;
    private final RoundPlanRepository roundPlanRepository;
    private final UserRepository userRepository;
    private final ShopService shopService;
    private final PlanningDeadlineService planningDeadlineService;
    private final Clock clock;
    /**
     * Polling reads (multi-table) use a single Postgres snapshot so concurrent
     * TX2/TX3 commits cannot mix old game.state/round with newer Keep HP / plans / shops.
     */
    private final TransactionTemplate repeatableReadTx;

    public GameService(
            GameRepository gameRepository,
            GamePlayerRepository gamePlayerRepository,
            RoundRepository roundRepository,
            RoundPlanRepository roundPlanRepository,
            UserRepository userRepository,
            ShopService shopService,
            PlanningDeadlineService planningDeadlineService,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.gameRepository = gameRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.roundRepository = roundRepository;
        this.roundPlanRepository = roundPlanRepository;
        this.userRepository = userRepository;
        this.shopService = shopService;
        this.planningDeadlineService = planningDeadlineService;
        this.clock = clock;
        this.repeatableReadTx = new TransactionTemplate(transactionManager);
        this.repeatableReadTx.setReadOnly(true);
        this.repeatableReadTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
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

        Instant now = clock.instant();
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

    /**
     * Deadline auto-lock (if any) commits first; the lobby/state projection then
     * loads under REPEATABLE READ so it cannot tear across concurrent resolve/advance.
     */
    public GameResponse getGame(UUID gameId, UUID userId) {
        mdcGame(gameId);
        assertParticipant(gameId, userId);
        finalizeExpiredPlanningIfNeeded(gameId);
        return repeatableReadTx.execute(status -> toGameResponse(loadGame(gameId)));
    }

    /**
     * Same snapshot isolation as {@link #getGame}: finalize outside the read TX,
     * then assemble game + round + plans + shops + Keep HP from one Postgres snapshot.
     */
    public GameStateResponse getState(UUID gameId, UUID userId) {
        mdcGame(gameId);
        assertParticipant(gameId, userId);
        finalizeExpiredPlanningIfNeeded(gameId);
        return repeatableReadTx.execute(status -> assembleState(gameId, userId));
    }

    private GameStateResponse assembleState(UUID gameId, UUID userId) {
        Game game = loadGame(gameId);

        if (GameStates.WAITING_FOR_PLAYERS.equals(game.getState())) {
            throw new GameNotReadyException(gameId);
        }

        Round round = roundRepository.findByGameIdAndRoundNumber(gameId, game.getCurrentRound())
                .orElseThrow(() -> new IllegalStateException("Missing round for game " + gameId));
        RoundPlan yourPlan = roundPlanRepository.findByRoundIdAndPlayerId(round.getId(), userId)
                .orElseThrow(() -> new IllegalStateException("Missing plan for player " + userId));

        UUID opponentId = resolveOpponentId(game, userId);
        RoundPlan opponentPlan = roundPlanRepository
                .findByRoundIdAndPlayerId(round.getId(), opponentId)
                .orElseThrow(() -> new IllegalStateException("Missing plan for opponent " + opponentId));

        // Viewer shop only — never load opponent offers.
        // Seat Keep HP is read after plan/shop so a concurrent TX2/TX3 commit that lands
        // mid-assemble is covered by the same REPEATABLE READ snapshot as {@code game}.
        PlanningShop yourShop = shopService.loadShop(round.getId(), userId);

        GamePlayer you = gamePlayerRepository.findByGameIdAndPlayerId(gameId, userId)
                .orElseThrow(() -> new NotGameParticipantException(gameId, userId));
        GamePlayer opponent = gamePlayerRepository.findByGameIdAndPlayerId(gameId, opponentId)
                .orElseThrow(() -> new IllegalStateException("Missing seat for opponent " + opponentId));

        PlanningState yourState = PlanningStateMapper.toPlanningState(
                yourPlan, yourShop, round.getRoundNumber());
        PlanningState opponentState = PlanningStateMapper.toPlanningState(
                opponentPlan, PlanningShop.empty(), round.getRoundNumber());

        Integer latestResolvedRound = roundRepository
                .findLatestResolvedRoundNumber(gameId)
                .orElse(null);

        return new GameStateResponse(
                game.getId(),
                game.getState(),
                game.getCurrentRound(),
                latestResolvedRound,
                you.getSeat(),
                you.getKeepHp(),
                yourPlan.getGold(),
                opponent.getKeepHp(),
                countUnits(opponentState),
                PlanningStateMapper.toBoardIdGrid(yourState.getBoard()),
                PlanningStateMapper.toUnitViews(yourState),
                PlanningStateMapper.toLaneDtos(yourState.getLane()),
                PlanningStateMapper.toShopDtos(yourShop),
                round.getPlanningDeadline(),
                yourPlan.isLocked(),
                opponentPlan.isLocked(),
                combatUnits(game.getState(), yourState, you.getSeat(), opponentState, opponent.getSeat()));
    }

    static List<CombatUnitDto> combatUnits(String phase, PlanningState yours, int yourSeat,
                                                PlanningState theirs, int theirSeat) {
        if (!GameStates.LOCKED.equals(phase) && !GameStates.RESOLVING.equals(phase)
                && !GameStates.ROUND_RESULT.equals(phase)) return List.of();
        List<CombatUnitDto> units = new ArrayList<>();
        appendCombatUnits(units, yours, yourSeat);
        appendCombatUnits(units, theirs, theirSeat);
        return List.copyOf(units);
    }

    private static void appendCombatUnits(List<CombatUnitDto> units, PlanningState state, int seat) {
        PlanningUnit[][] board = state.getBoard();
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                PlanningUnit unit = board[x][y];
                if (unit != null) units.add(new CombatUnitDto(unit.getId(), unit.getType(), unit.getLevel(),
                        seat, Coordinates.toCombatX(x, seat), Coordinates.toCombatY(y, seat)));
            }
        }
    }

    private static UUID resolveOpponentId(Game game, UUID userId) {
        if (userId.equals(game.getPlayer1Id())) {
            return game.getPlayer2Id();
        }
        if (userId.equals(game.getPlayer2Id())) {
            return game.getPlayer1Id();
        }
        throw new NotGameParticipantException(game.getId(), userId);
    }

    private Game loadGame(UUID gameId) {
        return gameRepository.findById(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
    }

    /**
     * Opportunistic deadline finalize before the RR snapshot is taken, so auto-lock
     * is visible to the subsequent read. {@link PlanningDeadlineService#autoLockIfDeadlinePassed}
     * no-ops unless the game/round are still PREPARATION and past the deadline.
     */
    private void finalizeExpiredPlanningIfNeeded(UUID gameId) {
        planningDeadlineService.autoLockIfDeadlinePassed(gameId);
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
        roundPlanRepository.save(new RoundPlan(roundId, playerId, STARTING_GOLD));
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

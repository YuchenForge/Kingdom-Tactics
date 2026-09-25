package com.kingdom.api.service;

import com.kingdom.api.dto.CommandResponse;
import com.kingdom.api.dto.RelocateRequest;
import com.kingdom.api.entity.Command;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.exception.ConflictException;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.exception.GameNotReadyException;
import com.kingdom.api.exception.PlanningCommandException;
import com.kingdom.api.exception.RoundLockedException;
import com.kingdom.api.exception.RoundNotFoundException;
import com.kingdom.api.exception.WrongGameStateException;
import com.kingdom.api.mapper.PlanningStateMapper;
import com.kingdom.api.repository.CommandRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.engine.planning.CommandApplier;
import com.kingdom.engine.planning.PlanningCommand;
import com.kingdom.engine.planning.PlanningError;
import com.kingdom.engine.planning.PlanningResult;
import com.kingdom.engine.planning.PlanningShop;
import com.kingdom.engine.planning.PlanningState;
import com.kingdom.engine.planning.ShopGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    static final String TYPE_BUY = "BUY_UNIT";
    static final String TYPE_SELL = "SELL_UNIT";
    static final String TYPE_REFRESH = "REFRESH_SHOP";
    static final String TYPE_RELOCATE = "RELOCATE_UNIT";
    static final String TYPE_LOCK = "LOCK_BOARD";

    /** Postgres UNIQUE (round_plan_id, idempotency_key) on commands. */
    private static final String IDEMPOTENCY_KEY_CONSTRAINT = "commands_round_plan_id_idempotency_key";

    private final GameService gameService;
    private final GameRepository gameRepository;
    private final RoundRepository roundRepository;
    private final RoundPlanRepository roundPlanRepository;
    private final CommandRepository commandRepository;
    private final ShopService shopService;
    private final CommandApplier commandApplier;
    private final PlanningDeadlineService planningDeadlineService;
    private final Clock clock;
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate requiresNewTx;

    public CommandService(
            GameService gameService,
            GameRepository gameRepository,
            RoundRepository roundRepository,
            RoundPlanRepository roundPlanRepository,
            CommandRepository commandRepository,
            ShopService shopService,
            CommandApplier commandApplier,
            PlanningDeadlineService planningDeadlineService,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.gameService = gameService;
        this.gameRepository = gameRepository;
        this.roundRepository = roundRepository;
        this.roundPlanRepository = roundPlanRepository;
        this.commandRepository = commandRepository;
        this.shopService = shopService;
        this.commandApplier = commandApplier;
        this.planningDeadlineService = planningDeadlineService;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Plan, command, and shop must come from one committed snapshot during retries.
        this.requiresNewTx.setReadOnly(true);
        this.requiresNewTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public CommandResponse buy(
            UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey, int shopSlot) {
        return executeCommand(gameId, roundNumber, userId, idempotencyKey, false, ctx -> {
            Map<String, Object> params = new HashMap<>();
            params.put("shopSlot", shopSlot);
            return new PreparedCommand(
                    new PlanningCommand.Buy(shopSlot),
                    TYPE_BUY,
                    params,
                    () -> shopService.consumeOffer(ctx.round().getId(), userId, shopSlot));
        });
    }

    public CommandResponse refresh(UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey) {
        return executeCommand(gameId, roundNumber, userId, idempotencyKey, false, ctx -> {
            int refreshIndex = 1 + (int) commandRepository.countByRoundPlanIdAndCommandType(
                    ctx.plan().getId(), TYPE_REFRESH);
            List<String> types = ShopGenerator.generateOfferTypes(
                    gameId, roundNumber, userId, refreshIndex);

            Map<String, Object> params = new HashMap<>();
            params.put("refreshIndex", refreshIndex);
            params.put("offers", types);
            return new PreparedCommand(
                    new PlanningCommand.Refresh(types),
                    TYPE_REFRESH,
                    params,
                    () -> shopService.persistOffers(ctx.round().getId(), userId, types));
        });
    }

    public CommandResponse sell(
            UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey, String unitId) {
        return executeCommand(gameId, roundNumber, userId, idempotencyKey, false, ctx -> {
            Map<String, Object> params = new HashMap<>();
            params.put("unitId", unitId);
            return new PreparedCommand(
                    new PlanningCommand.Sell(unitId),
                    TYPE_SELL,
                    params,
                    null);
        });
    }

    public CommandResponse relocate(
            UUID gameId,
            int roundNumber,
            UUID userId,
            UUID idempotencyKey,
            String unitId,
            RelocateRequest.Destination to) {
        return executeCommand(gameId, roundNumber, userId, idempotencyKey, false, ctx -> {
            PlanningCommand command = switch (to.type()) {
                case BOARD -> PlanningCommand.Relocate.toBoard(unitId, to.x(), to.y());
                case LANE -> PlanningCommand.Relocate.toLane(unitId, to.slot());
            };

            Map<String, Object> destination = new HashMap<>();
            destination.put("type", to.type().name());
            if (to.type() == RelocateRequest.DestinationType.BOARD) {
                destination.put("x", to.x());
                destination.put("y", to.y());
            } else {
                destination.put("slot", to.slot());
            }

            Map<String, Object> params = new HashMap<>();
            params.put("unitId", unitId);
            params.put("to", destination);
            return new PreparedCommand(command, TYPE_RELOCATE, params, null);
        });
    }

    public CommandResponse lock(UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey) {
        return executeCommand(gameId, roundNumber, userId, idempotencyKey, true, ctx ->
                new PreparedCommand(new PlanningCommand.Lock(), TYPE_LOCK, Map.of(), null));
    }

    /**
     * Runs the command in its own transaction. Integrity / optimistic-lock failures mark that
     * TX rollback-only; recovery (if any) happens here after it has rolled back.
     */
    private CommandResponse executeCommand(
            UUID gameId,
            int roundNumber,
            UUID userId,
            UUID idempotencyKey,
            boolean lockEndpoint,
            Function<CommandContext, PreparedCommand> prepare) {
        try {
            return txTemplate.execute(status ->
                    execute(gameId, roundNumber, userId, idempotencyKey, lockEndpoint, prepare));
        } catch (DataIntegrityViolationException ex) {
            return recoverFromIdempotencyConflict(
                    ex, gameId, roundNumber, userId, idempotencyKey, lockEndpoint);
        } catch (OptimisticLockingFailureException ex) {
            CommandResponse recovered = loadCommittedIdempotentSnapshot(
                    gameId, roundNumber, userId, idempotencyKey, lockEndpoint);
            if (recovered != null) {
                log.info("Optimistic lock race; returning committed idempotent snapshot");
                return recovered;
            }
            throw new ConflictException(
                    "CONFLICT",
                    "Plan was updated concurrently; retry with the same Idempotency-Key");
        }
    }

    private CommandResponse execute(
            UUID gameId,
            int roundNumber,
            UUID userId,
            UUID idempotencyKey,
            boolean lockEndpoint,
            Function<CommandContext, PreparedCommand> prepare) {

        mdcGame(gameId);
        // 1. participant
        gameService.assertParticipant(gameId, userId);

        // 2. load
        CommandContext ctx = loadContext(gameId, roundNumber, userId);

        // 3. guard PREPARATION / current round (LOCKED deferred to step 5 for idempotency)
        guardPreparation(ctx, roundNumber);

        // 4. deadline → auto-lock unlocked plans (may transition to LOCKED)
        boolean pastDeadline = planningDeadlineService.enforceDeadlineOrAutoLock(
                ctx.game(), ctx.round());
        if (lockEndpoint && !pastDeadline) {
            // Match deadline/worker lock order before taking a plan-row write lock.
            planningDeadlineService.lockForManualCommand(ctx.game(), ctx.round(), ctx.plan());
            guardPreparation(ctx, roundNumber);
        }

        // 5. if locked / past deadline: idempotent hit → snapshot; else → 423
        if (commandRepository
                .findByRoundPlanIdAndIdempotencyKey(ctx.plan().getId(), idempotencyKey)
                .isPresent()) {
            log.info("Idempotent hit commandTypeEndpoint={}", lockEndpoint ? TYPE_LOCK : "MUTATION");
            // Fresh TX so response matches committed plan/shop (not a stale L1 entity).
            CommandResponse committed = loadCommittedIdempotentSnapshot(
                    gameId, roundNumber, userId, idempotencyKey, lockEndpoint);
            return committed != null ? committed : currentResponse(ctx, lockEndpoint);
        }
        if (pastDeadline) {
            throw new PlanningCommandException(PlanningError.DEADLINE_PASSED);
        }
        if (GameStates.LOCKED.equals(ctx.game().getState())
                || GameStates.LOCKED.equals(ctx.round().getState())) {
            throw new RoundLockedException(ctx.game().getId());
        }

        PlanningShop shop = shopService.loadShop(ctx.round().getId(), userId);
        PlanningState state = PlanningStateMapper.toPlanningState(ctx.plan(), shop, roundNumber);
        PreparedCommand prepared = prepare.apply(ctx);

        PlanningResult result = commandApplier.apply(state, prepared.command());
        if (result.isFail()) {
            // Concurrent same-key winner may have already committed (e.g. sold the shop
            // slot) after we missed the initial idempotency lookup.
            CommandResponse recovered = loadCommittedIdempotentSnapshot(
                    gameId, roundNumber, userId, idempotencyKey, lockEndpoint);
            if (recovered != null) {
                log.info("Apply failed after concurrent idempotent commit; returning snapshot error={}",
                        result.getError());
                return recovered;
            }
            throw new PlanningCommandException(result.getError());
        }

        PlanningState next = result.getState();
        PlanningStateMapper.applyToPlan(ctx.plan(), next);
        if (lockEndpoint && next.isLocked() && ctx.plan().getLockedAt() == null) {
            ctx.plan().setLockedAt(clock.instant());
        }
        if (prepared.afterSuccess() != null) {
            prepared.afterSuccess().run();
        }
        roundPlanRepository.saveAndFlush(ctx.plan());

        if (lockEndpoint) {
            planningDeadlineService.maybeTransitionBothLocked(ctx.game(), ctx.round());
        }

        int sequence = commandRepository.countByRoundPlanId(ctx.plan().getId());
        commandRepository.saveAndFlush(new Command(
                ctx.plan().getId(),
                sequence,
                prepared.commandType(),
                prepared.parameters(),
                idempotencyKey));

        log.info("Command applied type={} sequencePlanId={}", prepared.commandType(), ctx.plan().getId());
        return responseFor(ctx, next, lockEndpoint);
    }

    /**
     * After the failed TX has rolled back: only treat the known idempotency UNIQUE as a
     * duplicate, and only return success when that key is actually committed.
     */
    private CommandResponse recoverFromIdempotencyConflict(
            DataIntegrityViolationException ex,
            UUID gameId,
            int roundNumber,
            UUID userId,
            UUID idempotencyKey,
            boolean lockEndpoint) {
        if (!isIdempotencyKeyConflict(ex)) {
            throw ex;
        }
        CommandResponse recovered = loadCommittedIdempotentSnapshot(
                gameId, roundNumber, userId, idempotencyKey, lockEndpoint);
        if (recovered == null) {
            throw ex;
        }
        log.info("Idempotency key race; returning committed snapshot");
        return recovered;
    }

    private CommandResponse loadCommittedIdempotentSnapshot(
            UUID gameId,
            int roundNumber,
            UUID userId,
            UUID idempotencyKey,
            boolean lockEndpoint) {
        return requiresNewTx.execute(status -> {
            CommandContext fresh = loadContext(gameId, roundNumber, userId);
            if (commandRepository
                    .findByRoundPlanIdAndIdempotencyKey(fresh.plan().getId(), idempotencyKey)
                    .isEmpty()) {
                return null;
            }
            return currentResponse(fresh, lockEndpoint);
        });
    }

    static boolean isIdempotencyKeyConflict(DataIntegrityViolationException ex) {
        String detail = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase();
        return detail.contains(IDEMPOTENCY_KEY_CONSTRAINT)
                || detail.contains("idempotency_key");
    }

    private CommandContext loadContext(UUID gameId, int roundNumber, UUID userId) {
        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        Round round = roundRepository.findByGameIdAndRoundNumber(gameId, roundNumber)
                .orElseThrow(() -> new RoundNotFoundException(gameId, roundNumber));
        RoundPlan plan = roundPlanRepository.findByRoundIdAndPlayerId(round.getId(), userId)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing plan for player " + userId + " round " + round.getId()));
        return new CommandContext(game, round, plan, userId);
    }

    /**
     * Allows PREPARATION and LOCKED through so step 5 can return an idempotent
     * snapshot after lock. Other states / wrong round still fail here.
     */
    private static void guardPreparation(CommandContext ctx, int roundNumber) {
        Game game = ctx.game();
        Round round = ctx.round();

        if (GameStates.WAITING_FOR_PLAYERS.equals(game.getState())) {
            throw new GameNotReadyException(game.getId());
        }
        boolean preparation = GameStates.PREPARATION.equals(game.getState())
                && GameStates.PREPARATION.equals(round.getState());
        boolean locked = GameStates.LOCKED.equals(game.getState())
                || GameStates.LOCKED.equals(round.getState());
        if (!preparation && !locked) {
            throw new WrongGameStateException(
                    game.getId(),
                    "Game is not in PREPARATION (state=" + game.getState() + ")");
        }
        if (roundNumber != game.getCurrentRound()) {
            throw new WrongGameStateException(
                    game.getId(),
                    "Round " + roundNumber + " is not the current round ("
                            + game.getCurrentRound() + ")");
        }
    }

    private RoundPlan opponentPlan(CommandContext ctx) {
        UUID opponentId = ctx.userId().equals(ctx.game().getPlayer1Id())
                ? ctx.game().getPlayer2Id()
                : ctx.game().getPlayer1Id();
        if (opponentId == null) {
            throw new IllegalStateException("Opponent missing for game " + ctx.game().getId());
        }
        return roundPlanRepository
                .findByRoundIdAndPlayerId(ctx.round().getId(), opponentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing plan for opponent " + opponentId));
    }

    private CommandResponse currentResponse(CommandContext ctx, boolean lockEndpoint) {
        PlanningShop shop = shopService.loadShop(ctx.round().getId(), ctx.userId());
        PlanningState state = PlanningStateMapper.toPlanningState(
                ctx.plan(), shop, ctx.round().getRoundNumber());
        return responseFor(ctx, state, lockEndpoint);
    }

    private CommandResponse responseFor(
            CommandContext ctx, PlanningState state, boolean lockEndpoint) {
        if (lockEndpoint) {
            boolean opponentLocked = opponentPlan(ctx).isLocked();
            String message = opponentLocked
                    ? "Both players locked! Combat will begin shortly."
                    : "Board locked. Waiting for opponent...";
            String nextState = opponentLocked ? GameStates.LOCKED : null;
            return CommandResponse.lock(
                    state.getGold(),
                    PlanningStateMapper.toLaneDtos(state.getLane()),
                    PlanningStateMapper.toBoardIdGrid(state.getBoard()),
                    PlanningStateMapper.toUnitViews(state),
                    PlanningStateMapper.toShopDtos(state.getShop()),
                    opponentLocked,
                    message,
                    nextState);
        }
        return PlanningStateMapper.toSnapshotResponse(state);
    }

    private static void mdcGame(UUID gameId) {
        if (gameId != null) {
            MDC.put("gameId", gameId.toString());
        }
    }

    private record CommandContext(Game game, Round round, RoundPlan plan, UUID userId) {
    }

    private record PreparedCommand(
            PlanningCommand command,
            String commandType,
            Map<String, Object> parameters,
            Runnable afterSuccess) {
    }
}

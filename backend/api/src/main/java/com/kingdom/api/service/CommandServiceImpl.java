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
import com.kingdom.engine.planning.PlanningResult;
import com.kingdom.engine.planning.PlanningShop;
import com.kingdom.engine.planning.PlanningState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Service
public class CommandServiceImpl implements CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandServiceImpl.class);

    static final String TYPE_BUY = "BUY_UNIT";
    static final String TYPE_SELL = "SELL_UNIT";
    static final String TYPE_REFRESH = "REFRESH_SHOP";
    static final String TYPE_RELOCATE = "RELOCATE_UNIT";
    static final String TYPE_LOCK = "LOCK_BOARD";

    private final GameService gameService;
    private final GameRepository gameRepository;
    private final RoundRepository roundRepository;
    private final RoundPlanRepository roundPlanRepository;
    private final CommandRepository commandRepository;
    private final ShopService shopService;
    private final CommandApplier commandApplier;
    private final TransactionTemplate requiresNewTx;

    public CommandServiceImpl(
            GameService gameService,
            GameRepository gameRepository,
            RoundRepository roundRepository,
            RoundPlanRepository roundPlanRepository,
            CommandRepository commandRepository,
            ShopService shopService,
            CommandApplier commandApplier,
            PlatformTransactionManager transactionManager) {
        this.gameService = gameService;
        this.gameRepository = gameRepository;
        this.roundRepository = roundRepository;
        this.roundPlanRepository = roundPlanRepository;
        this.commandRepository = commandRepository;
        this.shopService = shopService;
        this.commandApplier = commandApplier;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional
    public CommandResponse buy(
            UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey, int shopSlot) {
        return execute(gameId, roundNumber, userId, idempotencyKey, false, ctx -> {
            Map<String, Object> params = new HashMap<>();
            params.put("shopSlot", shopSlot);
            return new PreparedCommand(
                    new PlanningCommand.Buy(shopSlot),
                    TYPE_BUY,
                    params,
                    () -> shopService.consumeOffer(ctx.round().getId(), userId, shopSlot));
        });
    }

    @Override
    @Transactional
    public CommandResponse refresh(UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey) {
        return execute(gameId, roundNumber, userId, idempotencyKey, false, ctx -> {
            // Generate the refresh index
            int refreshIndex = 1 + (int) commandRepository.countByRoundPlanIdAndCommandType(
                    ctx.plan().getId(), TYPE_REFRESH);
            // Generate the offer types
            List<String> types = shopService.generateOfferTypes(
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

    @Override
    @Transactional
    public CommandResponse sell(
            UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey, String unitId) {
        return execute(gameId, roundNumber, userId, idempotencyKey, false, ctx -> {
            Map<String, Object> params = new HashMap<>();
            params.put("unitId", unitId);
            return new PreparedCommand(
                    new PlanningCommand.Sell(unitId),
                    TYPE_SELL,
                    params,
                    null);
        });
    }

    @Override
    @Transactional
    public CommandResponse relocate(
            UUID gameId,
            int roundNumber,
            UUID userId,
            UUID idempotencyKey,
            String unitId,
            RelocateRequest.Destination to) {
        return execute(gameId, roundNumber, userId, idempotencyKey, false, ctx -> {
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

    @Override
    @Transactional
    public CommandResponse lock(UUID gameId, int roundNumber, UUID userId, UUID idempotencyKey) {
        return execute(gameId, roundNumber, userId, idempotencyKey, true, ctx ->
                new PreparedCommand(new PlanningCommand.Lock(), TYPE_LOCK, Map.of(), null));
    }

    private CommandResponse execute(
            UUID gameId,
            int roundNumber,
            UUID userId,
            UUID idempotencyKey,
            boolean lockEndpoint,
            Function<CommandContext, PreparedCommand> prepare) {

        mdcGame(gameId);
        gameService.assertParticipant(gameId, userId);

        // Load game, round, plan and make sure the game is in PREPARATION state
        CommandContext ctx = loadContext(gameId, roundNumber, userId);
        guardPreparation(ctx, roundNumber);

        // Check if the command is idempotent, if it is, return the current response
        if (commandRepository
                .findByRoundPlanIdAndIdempotencyKey(ctx.plan().getId(), idempotencyKey)
                .isPresent()) {
            log.info("Idempotent hit commandTypeEndpoint={}", lockEndpoint ? TYPE_LOCK : "MUTATION");
            return currentResponse(ctx, lockEndpoint);
        }

        // Prepare the command 
        PlanningShop shop = shopService.loadShop(ctx.round().getId(), userId);
        PlanningState state = PlanningStateMapper.toPlanningState(ctx.plan(), shop, roundNumber);
        PreparedCommand prepared = prepare.apply(ctx);

        // Apply the command to the plan
        PlanningResult result = commandApplier.apply(state, prepared.command());
        if (result.isFail()) {
            throw new PlanningCommandException(result.getError());
        }

        // If successful, update the plan
        PlanningState next = result.getState();
        try {
            PlanningStateMapper.applyToPlan(ctx.plan(), next);
            if (lockEndpoint && next.isLocked() && ctx.plan().getLockedAt() == null) {
                ctx.plan().setLockedAt(Instant.now());
            }
            if (prepared.afterSuccess() != null) {
                prepared.afterSuccess().run();
            }
            // Save the plan to the database
            roundPlanRepository.saveAndFlush(ctx.plan());

            if (lockEndpoint) {
                maybeTransitionBothLocked(ctx);
            }

            // Save the command to the database
            int sequence = commandRepository.countByRoundPlanId(ctx.plan().getId());
            commandRepository.saveAndFlush(new Command(
                    ctx.plan().getId(),
                    sequence,
                    prepared.commandType(),
                    prepared.parameters(),
                    idempotencyKey));
            // Handle two request loaded the same plan concurrently
        } catch (OptimisticLockingFailureException ex) {
            throw new ConflictException(
                    "CONFLICT",
                    "Plan was updated concurrently; retry with the same Idempotency-Key");
            // Unique (round_plan_id, idempotency_key) race — winner committed; return snapshot
        } catch (DataIntegrityViolationException ex) {
            log.info("Idempotency key race; returning committed snapshot");
            return requiresNewTx.execute(status -> {
                CommandContext fresh = loadContext(gameId, roundNumber, userId);
                return currentResponse(fresh, lockEndpoint);
            });
        }
        // Log the command applied
        log.info("Command applied type={} sequencePlanId={}", prepared.commandType(), ctx.plan().getId());
        return responseFor(ctx, next, lockEndpoint);
    }

    // Load the game, round, plan 
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

    // Make sure the game is in PREPARATION state
    private static void guardPreparation(CommandContext ctx, int roundNumber) {
        Game game = ctx.game();
        Round round = ctx.round();

        if (GameStates.LOCKED.equals(game.getState()) || GameStates.LOCKED.equals(round.getState())) {
            throw new RoundLockedException(game.getId());
        }
        if (GameStates.WAITING_FOR_PLAYERS.equals(game.getState())) {
            throw new GameNotReadyException(game.getId());
        }
        if (!GameStates.PREPARATION.equals(game.getState())
                || !GameStates.PREPARATION.equals(round.getState())) {
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

    // If both players are locked, transition the game and round to LOCKED state
    private void maybeTransitionBothLocked(CommandContext ctx) {
        RoundPlan opponent = opponentPlan(ctx);
        if (!opponent.isLocked()) {
            return;
        }
        ctx.game().setState(GameStates.LOCKED);
        ctx.round().setState(GameStates.LOCKED);
        gameRepository.save(ctx.game());
        roundRepository.save(ctx.round());
        log.info("Both players locked; game={} → LOCKED", ctx.game().getId());
    }

    // Load the opponent's plan
    private RoundPlan opponentPlan(CommandContext ctx) {
        // Get the opponent's ID
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

    // Return the current response
    private CommandResponse currentResponse(CommandContext ctx, boolean lockEndpoint) {
        PlanningShop shop = shopService.loadShop(ctx.round().getId(), ctx.userId());
        PlanningState state = PlanningStateMapper.toPlanningState(
                ctx.plan(), shop, ctx.round().getRoundNumber());
        return responseFor(ctx, state, lockEndpoint);
    }

    // Return the response for the current state
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
                    PlanningStateMapper.toShopDtos(state.getShop()),
                    opponentLocked,
                    message,
                    nextState);
        }
        return PlanningStateMapper.toSnapshotResponse(state);
    }

    // Set the game ID in the MDC
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

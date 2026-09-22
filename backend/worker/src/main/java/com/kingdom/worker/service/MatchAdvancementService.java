package com.kingdom.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.RoundPlan;
import com.kingdom.api.entity.User;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundPlanRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import com.kingdom.api.service.GameService;
import com.kingdom.api.service.ShopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TX3 — advance a durable ROUND_RESULT: FINISHED (+ ratings) or next PREPARATION.
 * Does not re-apply Keep damage or mutate locked round_plans / historical round state.
 */
@Service
public class MatchAdvancementService {

    private static final Logger log = LoggerFactory.getLogger(MatchAdvancementService.class);

    static final int GOLD_PER_ROUND = 5;
    static final int RATING_DELTA = 25;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RoundRepository roundRepository;
    private final GameRepository gameRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final RoundPlanRepository roundPlanRepository;
    private final ShopService shopService;
    private final UserRepository userRepository;
    private final Clock clock;

    public MatchAdvancementService(
            RoundRepository roundRepository,
            GameRepository gameRepository,
            GamePlayerRepository gamePlayerRepository,
            RoundPlanRepository roundPlanRepository,
            ShopService shopService,
            UserRepository userRepository,
            Clock clock) {
        this.roundRepository = roundRepository;
        this.gameRepository = gameRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.roundPlanRepository = roundPlanRepository;
        this.shopService = shopService;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * Advance once after TX2.
     *
     * @return true if this worker advanced; false if already advanced / not ROUND_RESULT
     */
    @Transactional
    public boolean advance(UUID roundId) {
        // Lock order: round → game → players (seat) → users (id, finish only)
        Round round = roundRepository.lockUnadvancedRoundResult(roundId).orElse(null);
        if (round == null) {
            return false;
        }

        UUID gameId = round.getGameId();
        Game game = gameRepository.lockGameForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        List<GamePlayer> players = gamePlayerRepository.lockByGameIdOrderBySeatAsc(gameId);
        if (players.size() != 2) {
            throw new IllegalStateException(
                    "Expected 2 game_players for gameId=" + gameId + ", found " + players.size());
        }
        GamePlayer p0 = players.get(0);
        GamePlayer p1 = players.get(1);
        if (p0.getSeat() != 0 || p1.getSeat() != 1) {
            throw new IllegalStateException(
                    "Expected seats 0 and 1 for gameId=" + gameId
                            + ", found " + p0.getSeat() + " and " + p1.getSeat());
        }

        List<RoundPlan> oldPlans = roundPlanRepository.findByRoundId(roundId);
        int gold0 = planGold(oldPlans, p0.getPlayerId());
        int gold1 = planGold(oldPlans, p1.getPlayerId());

        MatchEndDecision decision = MatchEndDecision.decide(
                p0.getKeepHp(), p1.getKeepHp(), round.getRoundNumber(), gold0, gold1);

        Instant now = clock.instant();
        if (decision.isFinished()) {
            finishMatch(game, players, decision, now);
        } else {
            startNextRound(game, round, players, oldPlans, now);
        }

        round.setAdvancedAt(now);
        // round.state stays ROUND_RESULT
        return true;
    }

    /**
     * Finish branch: games → FINISHED; winner_id = victor user UUID or null (draw).
     * Seat → GamePlayer.playerId → User. Ratings in-place on users.rating (±25; draw skips).
     */
    private void finishMatch(
            Game game, List<GamePlayer> players, MatchEndDecision decision, Instant now) {
        Integer winnerSeat = decision.winnerSeat();
        UUID winnerId = winnerSeat == null ? null : players.get(winnerSeat).getPlayerId();

        game.setState(GameStates.FINISHED);
        game.setWinnerId(winnerId);
        game.setFinishedAt(now);

        // Draw → no rating change
        if (winnerId != null) {
            applyRatings(players.get(0).getPlayerId(), players.get(1).getPlayerId(), winnerId);
        }

        log.debug("TX3 finished gameId={} winnerId={} draw={}",
                game.getId(), winnerId, decision.isDraw());
    }

    /** Lock both users in id order, then winner +25 / loser -25. */
    private void applyRatings(UUID player0Id, UUID player1Id, UUID winnerId) {
        List<User> users = userRepository.lockTwoByIdsOrderByIdAsc(player0Id, player1Id);
        if (users.size() != 2) {
            throw new IllegalStateException(
                    "Expected 2 users for rating update, found " + users.size());
        }
        for (User user : users) {
            if (user.getId().equals(winnerId)) {
                user.setRating(user.getRating() + RATING_DELTA);
            } else {
                user.setRating(user.getRating() - RATING_DELTA);
            }
        }
    }

    private void startNextRound(
            Game game,
            Round completed,
            List<GamePlayer> players,
            List<RoundPlan> oldPlans,
            Instant now) {
        int next = completed.getRoundNumber() + 1;
        // MatchEndDecision only continues when round < 8, so next is always ≤ 8
        if (next > MatchEndDecision.FINAL_ROUND) {
            throw new IllegalStateException(
                    "Continue branch produced round " + next + " (> " + MatchEndDecision.FINAL_ROUND + ")");
        }

        UUID gameId = game.getId();
        UUID p0Id = players.get(0).getPlayerId();
        UUID p1Id = players.get(1).getPlayerId();

        Round nextRound = roundRepository.save(new Round(
                gameId,
                next,
                GameStates.PREPARATION,
                now.plusSeconds(GameService.PLANNING_SECONDS)));

        for (RoundPlan old : oldPlans) {
            roundPlanRepository.save(copyPlanForNextRound(nextRound.getId(), old));
        }

        // New shops for round N+1 (refreshIndex 0); do not copy sold slots from N
        shopService.createShopsForRound(nextRound, gameId, p0Id, p1Id);

        game.setCurrentRound(next);
        game.setState(GameStates.PREPARATION);

        log.debug("TX3 next PREPARATION gameId={} round={}", gameId, next);
    }

    /**
     * New unlocked plan: same playerId, deep-copied board/lane JSONB, gold = old + 5.
     * Does not read end snapshots / survivors.
     */
    private RoundPlan copyPlanForNextRound(UUID nextRoundId, RoundPlan old) {
        RoundPlan next = new RoundPlan(nextRoundId, old.getPlayerId(), old.getGold() + GOLD_PER_ROUND);
        next.setBoardState(copyMap(old.getBoardState()));
        next.setLaneUnits(copyList(old.getLaneUnits()));
        next.setLocked(false);
        next.setLockedAt(null);
        return next;
    }

    private static int planGold(List<RoundPlan> plans, UUID playerId) {
        return plans.stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst()
                .map(RoundPlan::getGold)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing round_plan for playerId=" + playerId));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new HashMap<>();
        }
        return JSON.convertValue(source, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> copyList(List<Object> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        return JSON.convertValue(source, List.class);
    }
}

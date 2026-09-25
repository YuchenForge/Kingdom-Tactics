package com.kingdom.api.service;

import com.kingdom.api.dto.CombatEventDto;
import com.kingdom.api.dto.EndSnapshotDto;
import com.kingdom.api.dto.EventsResponse;
import com.kingdom.api.dto.MatchResultResponse;
import com.kingdom.api.dto.RoundResultResponse;
import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameEvent;
import com.kingdom.api.entity.GamePlayer;
import com.kingdom.api.entity.GameStateSnapshot;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.entity.User;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.exception.RoundNotFoundException;
import com.kingdom.api.exception.WrongGameStateException;
import com.kingdom.api.repository.GameEventRepository;
import com.kingdom.api.repository.GamePlayerRepository;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.GameStateSnapshotRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.api.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only combat / result APIs — surfaces what the worker persisted in TX2/TX3.
 * No engine work on the request thread.
 */
@Service
public class GameResultService {

    static final int MIN_ROUND = 1;
    static final int MAX_ROUND = 8;
    static final int DEFAULT_EVENT_LIMIT = 200;
    static final int MAX_EVENT_LIMIT = 500;

    private final GameService gameService;
    private final GameRepository gameRepository;
    private final RoundRepository roundRepository;
    private final GameEventRepository gameEventRepository;
    private final GameStateSnapshotRepository snapshotRepository;
    private final GamePlayerRepository gamePlayerRepository;
    private final UserRepository userRepository;

    public GameResultService(
            GameService gameService,
            GameRepository gameRepository,
            RoundRepository roundRepository,
            GameEventRepository gameEventRepository,
            GameStateSnapshotRepository snapshotRepository,
            GamePlayerRepository gamePlayerRepository,
            UserRepository userRepository) {
        this.gameService = gameService;
        this.gameRepository = gameRepository;
        this.roundRepository = roundRepository;
        this.gameEventRepository = gameEventRepository;
        this.snapshotRepository = snapshotRepository;
        this.gamePlayerRepository = gamePlayerRepository;
        this.userRepository = userRepository;
    }

    /**
     * Page combat events for a round. Empty page with complete=false while RESOLVING
     * (or before TX2 commits) is correct — do not run the engine.
     * REPEATABLE READ: outcome/complete and event rows come from one snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EventsResponse getEvents(
            UUID gameId,
            UUID userId,
            int roundNumber,
            Integer afterSequence,
            Integer limit) {
        gameService.assertParticipant(gameId, userId);
        validateRoundNumber(roundNumber);

        int cursor = afterSequence == null ? 0 : afterSequence;
        if (cursor < 0) {
            throw new IllegalArgumentException("afterSequence must be >= 0");
        }
        int pageSize = resolveEventLimit(limit);

        Round round = roundRepository.findByGameIdAndRoundNumber(gameId, roundNumber)
                .orElseThrow(() -> new RoundNotFoundException(gameId, roundNumber));

        boolean complete = round.getOutcome() != null;

        // limit+1 to detect hasMore without a second count query
        List<GameEvent> fetched = gameEventRepository
                .findByGameIdAndRoundNumberAndSequenceNumGreaterThanOrderBySequenceNumAsc(
                        gameId,
                        roundNumber,
                        cursor,
                        PageRequest.of(0, pageSize + 1));

        boolean hasMore = fetched.size() > pageSize;
        List<GameEvent> page = hasMore ? fetched.subList(0, pageSize) : fetched;

        if (page.isEmpty()) {
            return new EventsResponse(roundNumber, List.of(), cursor, false, complete);
        }

        List<CombatEventDto> events = new ArrayList<>(page.size());
        for (GameEvent event : page) {
            events.add(new CombatEventDto(
                    event.getSequenceNum(),
                    event.getEventType(),
                    event.getTick(),
                    event.getData()));
        }
        int nextAfterSequence = page.get(page.size() - 1).getSequenceNum();
        return new EventsResponse(roundNumber, events, nextAfterSequence, hasMore, complete);
    }

    /**
     * Immutable round result after TX2. Historical Keep HP / survivors come from end snapshots,
     * never live game_players.keep_hp. REPEATABLE READ keeps outcome + snapshots aligned.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RoundResultResponse getRoundResult(UUID gameId, UUID userId, int roundNumber) {
        gameService.assertParticipant(gameId, userId);
        validateRoundNumber(roundNumber);

        Round round = roundRepository.findByGameIdAndRoundNumber(gameId, roundNumber)
                .orElseThrow(() -> new RoundNotFoundException(gameId, roundNumber));

        if (round.getOutcome() == null) {
            throw new WrongGameStateException(gameId, "Round " + roundNumber + " not yet resolved");
        }

        List<GamePlayer> seats = gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId);
        Map<UUID, String> seatKeyByPlayerId = seatKeysByPlayerId(seats);

        List<GameStateSnapshot> endSnaps = snapshotRepository
                .findByGameIdAndRoundNumberAndRoundStart(gameId, roundNumber, false);

        Map<String, Integer> keepHpAfter = new LinkedHashMap<>(2);
        Map<String, EndSnapshotDto> endSnapshots = new LinkedHashMap<>(2);
        for (GameStateSnapshot snap : endSnaps) {
            String seatKey = seatKeyByPlayerId.get(snap.getPlayerId());
            if (seatKey == null) {
                throw new IllegalStateException(
                        "End snapshot player " + snap.getPlayerId()
                                + " is not a seat in game " + gameId);
            }
            keepHpAfter.put(seatKey, snap.getKeepHp());
            endSnapshots.put(seatKey, new EndSnapshotDto(snap.getBoard(), snap.getKeepHp()));
        }

        Map<String, Integer> keepDamage = round.getKeepDamage() != null
                ? Map.copyOf(round.getKeepDamage())
                : Map.of();

        return new RoundResultResponse(
                roundNumber,
                round.getOutcome(),
                keepDamage,
                keepHpAfter,
                endSnapshots);
    }

    /**
     * Match result when FINISHED. Draw → winnerId / usernames all null.
     * REPEATABLE READ: FINISHED game row + final Keep HP from one snapshot.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MatchResultResponse getMatchResult(UUID gameId, UUID userId) {
        gameService.assertParticipant(gameId, userId);

        Game game = gameRepository.findById(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (!GameStates.FINISHED.equals(game.getState())) {
            throw new WrongGameStateException(
                    gameId,
                    "Match is not finished (state=" + game.getState() + ")");
        }

        List<GamePlayer> seats = gamePlayerRepository.findByGameIdOrderBySeatAsc(gameId);
        List<Integer> finalKeepHp = seats.stream().map(GamePlayer::getKeepHp).toList();

        UUID winnerId = game.getWinnerId();
        String winnerUsername = null;
        String loserUsername = null;
        if (winnerId != null) {
            User winner = userRepository.findById(winnerId)
                    .orElseThrow(() -> new IllegalStateException("Missing winner user " + winnerId));
            winnerUsername = winner.getUsername();
            UUID loserId = seats.stream()
                    .map(GamePlayer::getPlayerId)
                    .filter(id -> !id.equals(winnerId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing loser for game " + gameId));
            User loser = userRepository.findById(loserId)
                    .orElseThrow(() -> new IllegalStateException("Missing loser user " + loserId));
            loserUsername = loser.getUsername();
        }

        Instant finishedAt = game.getFinishedAt();
        Instant started = game.getStartedAt() != null ? game.getStartedAt() : game.getCreatedAt();
        long durationSeconds = finishedAt != null && started != null
                ? Math.max(0, Duration.between(started, finishedAt).getSeconds())
                : 0L;

        return new MatchResultResponse(
                game.getId(),
                game.getState(),
                winnerId,
                winnerUsername,
                loserUsername,
                finalKeepHp,
                game.getCurrentRound(),
                durationSeconds,
                finishedAt);
    }

    private static void validateRoundNumber(int roundNumber) {
        if (roundNumber < MIN_ROUND || roundNumber > MAX_ROUND) {
            throw new IllegalArgumentException(
                    "round must be between " + MIN_ROUND + " and " + MAX_ROUND);
        }
    }

    private static int resolveEventLimit(Integer limit) {
        int resolved = limit == null ? DEFAULT_EVENT_LIMIT : limit;
        if (resolved < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        return Math.min(resolved, MAX_EVENT_LIMIT);
    }

    private static Map<UUID, String> seatKeysByPlayerId(List<GamePlayer> seats) {
        Map<UUID, String> keys = new HashMap<>(seats.size());
        for (GamePlayer seat : seats) {
            keys.put(seat.getPlayerId(), String.valueOf(seat.getSeat()));
        }
        return keys;
    }
}

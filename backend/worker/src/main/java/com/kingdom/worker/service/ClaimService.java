package com.kingdom.worker.service;

import com.kingdom.api.entity.Game;
import com.kingdom.api.entity.GameStates;
import com.kingdom.api.entity.Round;
import com.kingdom.api.exception.GameNotFoundException;
import com.kingdom.api.repository.GameRepository;
import com.kingdom.api.repository.RoundRepository;
import com.kingdom.engine.service.CombatSeedGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * TX1 — claim a LOCKED round: SKIP LOCKED, set RESOLVING + combat_seed, commit.
 * Does not run the engine or hold the TX across combat.
 */
@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    private final RoundRepository roundRepository;
    private final GameRepository gameRepository;

    public ClaimService(RoundRepository roundRepository, GameRepository gameRepository) {
        this.roundRepository = roundRepository;
        this.gameRepository = gameRepository;
    }

    /**
     * Lock order: round (SKIP LOCKED) then game — always the same to avoid deadlocks.
     *
     * @return empty if another worker holds the row or state is no longer LOCKED
     */
    @Transactional
    public Optional<ClaimedRound> claim(UUID roundId) {
        // lock the round for claim
        Round round = roundRepository.lockLockedRoundForClaim(roundId).orElse(null);
        if (round == null) {
            return Optional.empty();
        }

        // lock the game for update
        Game game = gameRepository.lockGameForUpdate(round.getGameId())
                .orElseThrow(() -> new GameNotFoundException(round.getGameId()));

        // if the game is not locked, log a warning
        if (!GameStates.LOCKED.equals(game.getState())) {
            log.warn("Round LOCKED but game state={} game={}; aligning both to RESOLVING",
                    game.getState(), game.getId());
        }

        // if the combat seed is not set, generate a new one
        if (round.getCombatSeed() == null) {
            round.setCombatSeed(CombatSeedGenerator.seed(round.getGameId(), round.getRoundNumber()));
        }

        // set the state to RESOLVING
        round.setState(GameStates.RESOLVING);
        game.setState(GameStates.RESOLVING);

        // return the claimed round
        return Optional.of(new ClaimedRound(
                round.getId(),
                round.getGameId(),
                round.getRoundNumber(),
                round.getCombatSeed()));
    }
}

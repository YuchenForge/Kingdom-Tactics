package com.kingdom.worker.service;

import com.kingdom.engine.domain.ResolutionResult;
import com.kingdom.engine.service.CombatEngine;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ResolutionService {

    private final CombatBoardLoader combatBoardLoader;

    public ResolutionService(CombatBoardLoader combatBoardLoader) {
        this.combatBoardLoader = combatBoardLoader;
    }

    // call the engine to resolve the combat
    public ResolutionResult resolve(UUID roundId, UUID gameId, long combatSeed) {
        LoadedCombatBoards boards = combatBoardLoader.load(gameId, roundId);
        return new CombatEngine(combatSeed).resolve(boards.board0(), boards.board1());
    }
}

package com.kingdom.worker.service;

import com.kingdom.engine.domain.Board;

/**
 * Placement boards for seats 0 and 1, ready for CombatEngine.resolve.
 */
public record LoadedCombatBoards(Board board0, Board board1) {
}

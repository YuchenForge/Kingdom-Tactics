import type { CombatOutcome, RoundResult } from '../types'
import { initialCombatState } from './combatState'
import type { CombatState, CombatUnitState } from './combatState'

export function roundOutcomeLabel(outcome: CombatOutcome, seat: number) {
  if (outcome === 'DRAW') return 'Draw'
  if (outcome === 'TIME_LIMIT') return 'Time limit reached'
  return (outcome === 'PLAYER_VICTORY') === (seat === 0) ? 'You won the round' : 'Opponent won the round'
}

/** A failed/missing recording must show saved survivors, not the starting formation. */
export function roundResultFrame(gameId: string, result: RoundResult): CombatState | null {
  if (![0, 1].every(seat => result.endSnapshots[String(seat)]?.survivors)) return null
  const units: Record<string, CombatUnitState> = {}
  for (const seat of [0, 1]) {
    for (const unit of result.endSnapshots[String(seat)].survivors) {
      units[unit.id] = { ...unit, seat, dead: false }
    }
  }
  return { ...initialCombatState(gameId, result.roundNumber), units, outcome: result.outcome }
}

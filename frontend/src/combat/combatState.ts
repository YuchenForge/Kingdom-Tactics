import type { CombatOutcome, RoundResult, SurvivingUnit } from '../types'
import type { CombatEvent, CombatRecording } from '../types/combat'

export type CombatUnitState = Readonly<SurvivingUnit & { seat: number; dead: boolean }>
export type CombatState = Readonly<{
  gameId: string
  roundNumber: number
  sequenceNumber: number
  tick: number
  units: Readonly<Record<string, CombatUnitState>>
  outcome: CombatOutcome | null
}>

export function initialCombatState(gameId: string, roundNumber: number): CombatState {
  return { gameId, roundNumber, sequenceNumber: 0, tick: 0, units: {}, outcome: null }
}

function requireUnit(state: CombatState, id: string): CombatUnitState {
  if (!Object.hasOwn(state.units, id)) throw new Error(`Unknown combat unit: ${id}`)
  return state.units[id]
}

/** One immutable server event, without running combat rules or discarding dead actors. */
export function reduceCombatEvent(state: CombatState, event: CombatEvent): CombatState {
  if (event.sequenceNumber !== state.sequenceNumber + 1 || event.tick < state.tick || state.outcome !== null) {
    throw new Error('Combat events must follow sequence and tick order, before COMBAT_ENDED.')
  }
  let unit: CombatUnitState | undefined
  let outcome: CombatOutcome | null = state.outcome
  switch (event.type) {
    case 'UNIT_PLACED': {
      const d = event.data
      if (Object.hasOwn(state.units, d.unitId)) throw new Error(`Duplicate combat unit: ${d.unitId}`)
      unit = { id: d.unitId, type: d.unitType, level: d.level, seat: d.playerId,
        x: d.x, y: d.y, currentHp: d.currentHp, maxHp: d.maxHp, dead: false }
      break
    }
    case 'UNIT_MOVED':
      unit = { ...requireUnit(state, event.data.unitId), x: event.data.x, y: event.data.y }
      break
    case 'ATTACK': {
      requireUnit(state, event.data.attackerId)
      const target = requireUnit(state, event.data.targetId)
      unit = { ...target, currentHp: Math.max(0, target.currentHp - event.data.damage) }
      break
    }
    case 'HEALED': {
      requireUnit(state, event.data.healerId)
      const target = requireUnit(state, event.data.targetId)
      unit = { ...target, currentHp: Math.min(target.maxHp, target.currentHp + event.data.amount) }
      break
    }
    case 'UNIT_DIED':
      unit = { ...requireUnit(state, event.data.unitId), currentHp: 0, dead: true }
      break
    case 'COMBAT_ENDED':
      outcome = event.data.reason
      break
    default: {
      const unsupported: never = event
      throw new Error(`Unsupported combat event: ${JSON.stringify(unsupported)}`)
    }
  }
  return { ...state, sequenceNumber: event.sequenceNumber, tick: event.tick, outcome,
    units: unit ? { ...state.units, [unit.id]: unit } : state.units }
}

/** Each step captures before/after values for later animation cues, including same-tick HP changes. */
export function reconstructCombat(recording: CombatRecording) {
  const initial = initialCombatState(recording.gameId, recording.roundNumber)
  let current = initial
  const steps = recording.events.map(event => {
    const before = current
    current = reduceCombatEvent(before, event)
    return { event, before, after: current }
  })
  return { initial, steps, final: current }
}

export type CombatTimeline = ReturnType<typeof reconstructCombat>

/** Inclusive tick lookup; empty ticks retain the previous state. No timers or viewer rotation. */
export function combatStateAtTick(timeline: CombatTimeline, tick: number): CombatState {
  let low = 0
  let high = timeline.steps.length
  while (low < high) {
    const mid = Math.floor((low + high) / 2)
    if (timeline.steps[mid].event.tick <= tick) low = mid + 1
    else high = mid
  }
  return low === 0 ? timeline.initial : timeline.steps[low - 1].after
}

export function combatSurvivors(state: CombatState, seat: number): SurvivingUnit[] {
  return Object.values(state.units).filter(unit => unit.seat === seat && !unit.dead && unit.currentHp > 0)
    .map(({ id, type, level, x, y, currentHp, maxHp }) => ({ id, type, level, x, y, currentHp, maxHp }))
    .sort((a, b) => a.id.localeCompare(b.id))
}

/** Compare against server history; Keep HP/damage remain owned by RoundResult, not reconstructed. */
export function matchesRoundResult(state: CombatState, result: RoundResult): boolean {
  if (state.roundNumber !== result.roundNumber || state.outcome !== result.outcome) return false
  return [0, 1].every(seat => {
    const expected = result.endSnapshots[String(seat)]?.survivors
    if (!expected) return false
    const actual = combatSurvivors(state, seat)
    const sorted = [...expected].sort((a, b) => a.id.localeCompare(b.id))
    return actual.length === sorted.length && actual.every((unit, index) => {
      const other = sorted[index]
      return unit.id === other.id && unit.type === other.type && unit.level === other.level
        && unit.x === other.x && unit.y === other.y && unit.currentHp === other.currentHp && unit.maxHp === other.maxHp
    })
  })
}

import type { CombatOutcome } from './index'

type EventData = {
  UNIT_PLACED: { unitId: string; unitType: string; x: number; y: number; playerId: number; level: number; currentHp: number; maxHp: number }
  UNIT_MOVED: { unitId: string; x: number; y: number; playerId: number }
  ATTACK: { attackerId: string; targetId: string; damage: number }
  HEALED: { healerId: string; targetId: string; amount: number }
  UNIT_DIED: { unitId: string }
  COMBAT_ENDED: { reason: CombatOutcome }
}

/** Preserve server sequence order, including multiple events on the same tick. */
export type CombatEvent = {
  [K in keyof EventData]: { sequenceNumber: number; tick: number; type: K; data: EventData[K] }
}[keyof EventData]

export type CombatEventsPage = {
  roundNumber: number
  events: CombatEvent[]
  nextAfterSequence: number
  hasMore: boolean
  complete: boolean
}

export type CombatRecording = { gameId: string; roundNumber: number; events: CombatEvent[] }

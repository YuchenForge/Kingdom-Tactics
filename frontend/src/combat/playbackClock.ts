import type { CombatPresentation } from '../types'
import { getState } from '../api/gameApi'

/** Client-only sample: server time estimated at receipt, anchored to a monotonic clock. */
export type ServerClockSample = { serverAtReceipt: number; receivedAt: number }

export async function getTimedGameState(gameId: string) {
  const sentAt = performance.now()
  const state = await getState(gameId)
  const receivedAt = performance.now()
  const serverTime = Date.parse(state.serverTime ?? '')
  if (!Number.isFinite(serverTime)) return state
  return { ...state, clockSample: { serverAtReceipt: serverTime + (receivedAt - sentAt) / 2, receivedAt } }
}

export function serverNow(sample: ServerClockSample, localNow: number): number {
  return sample.serverAtReceipt + localNow - sample.receivedAt
}

export function playbackPosition(timing: CombatPresentation, now: number) {
  const start = Date.parse(timing.startsAt)
  const combatEnd = Date.parse(timing.combatEndsAt)
  const end = Date.parse(timing.endsAt)
  if (![start, combatEnd, end, now].every(Number.isFinite) || combatEnd < start || end < combatEnd
      || !Number.isFinite(timing.tickDurationMs) || timing.tickDurationMs <= 0) {
    throw new Error('Invalid combat presentation timing.')
  }
  const phase = now < start ? 'lead-in' : now < combatEnd ? 'combat' : now < end ? 'result' : 'ended'
  const tick = Math.max(0, Math.floor((Math.min(now, combatEnd) - start) / timing.tickDurationMs))
  return { phase, tick, elapsedMs: Math.max(0, now - start) } as const
}

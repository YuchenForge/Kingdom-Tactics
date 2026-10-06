import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { GameState } from '../types'
import type { CombatRecording } from '../types/combat'
import { usePresentedGameState } from './usePresentedGameState'
import { useCombatPlayback } from './useCombatPlayback'
import { sampleCombatMotion } from '../combat/combatMotion'

const origin = Date.parse('2026-10-06T12:00:00Z')
const state = { gameId: 'g', currentRound: 8, state: 'ROUND_RESULT',
  clockSample: { serverAtReceipt: origin, receivedAt: 0 },
  combatPresentation: { roundNumber: 8, startsAt: new Date(origin).toISOString(),
    combatEndsAt: new Date(origin + 2000).toISOString(), endsAt: new Date(origin + 6000).toISOString(), tickDurationMs: 250 },
} as GameState
const recording: CombatRecording = { gameId: 'g', roundNumber: 8, events: [
  { sequenceNumber: 1, tick: 0, type: 'UNIT_PLACED', data: { unitId: 'a', unitType: 'Knight', playerId: 0, level: 1, x: 0, y: 0, currentHp: 18, maxHp: 18 } },
  { sequenceNumber: 2, tick: 0, type: 'UNIT_PLACED', data: { unitId: 'b', unitType: 'Squire', playerId: 1, level: 1, x: 0, y: 1, currentHp: 5, maxHp: 8 } },
  { sequenceNumber: 3, tick: 7, type: 'ATTACK', data: { attackerId: 'a', targetId: 'b', damage: 5 } },
  { sequenceNumber: 4, tick: 7, type: 'UNIT_DIED', data: { unitId: 'b' } },
  { sequenceNumber: 5, tick: 8, type: 'COMBAT_ENDED', data: { reason: 'PLAYER_VICTORY' } },
] }
let now = 0
beforeEach(() => { vi.useFakeTimers(); now = 0; vi.spyOn(performance, 'now').mockImplementation(() => now) })
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers() })
function advance(ms: number) { now = ms; act(() => vi.advanceTimersByTime(32)) }
function setup() {
  return renderHook(({ live, id }) => {
    const shown = usePresentedGameState(live, id)
    return { shown, playback: useCombatPlayback(shown, recording) }
  }, { initialProps: { live: state, id: 'g' } })
}
it('keeps a steady clock when a poll reports a large forward correction', () => {
  const hook = setup()
  advance(1500)
  hook.rerender({ live: { ...state, clockSample: { receivedAt: 1500, serverAtReceipt: origin + 5900 } }, id: 'g' })
  expect(hook.result.current.playback.elapsed).toBe(1500)
  expect(hook.result.current.playback.resultReady).toBe(false)
  advance(1910)
  const p = hook.result.current.playback
  expect(sampleCombatMotion(p.motion!, p.elapsed).effects.some(c => c.event.type === 'ATTACK')).toBe(true)
})
it.each(['FINISHED', 'PREPARATION'] as const)('finishes the last hit and death before releasing an early %s poll', phase => {
  const hook = setup()
  advance(1500)
  const live = { ...state, state: phase, currentRound: phase === 'PREPARATION' ? 9 : 8, combatPresentation: null }
  hook.rerender({ live, id: 'g' })
  expect(hook.result.current.shown?.state).toBe('ROUND_RESULT')
  expect(hook.result.current.playback.resultReady).toBe(false)
  advance(1909)
  let p = hook.result.current.playback
  expect(sampleCombatMotion(p.motion!, p.elapsed).state.units.b.currentHp).toBe(5)
  advance(1910)
  p = hook.result.current.playback
  expect(sampleCombatMotion(p.motion!, p.elapsed).state.units.b.currentHp).toBe(0)
  advance(2070)
  p = hook.result.current.playback
  expect(sampleCombatMotion(p.motion!, p.elapsed).units.find(u => u.unit.id === 'b')?.death).toBeGreaterThan(0)
  expect(p.resultReady).toBe(false)
  advance(3000)
  expect(hook.result.current.playback.resultReady).toBe(true)
  expect(hook.result.current.shown?.state).toBe('ROUND_RESULT')
  advance(6000)
  expect(hook.result.current.shown).toBe(live)
  expect(vi.getTimerCount()).toBe(0)
})
it('does not retain another game or restart already expired playback on reload', () => {
  const hook = setup()
  hook.rerender({ live: { ...state, gameId: 'other', state: 'PREPARATION' }, id: 'other' })
  expect(hook.result.current.shown?.gameId).toBe('other')
  hook.unmount()
  now = 7000
  const late = setup()
  expect(late.result.current.playback.position?.phase).toBe('ended')
  late.rerender({ live: { ...state, state: 'FINISHED' }, id: 'g' })
  expect(late.result.current.shown?.state).toBe('FINISHED')
})

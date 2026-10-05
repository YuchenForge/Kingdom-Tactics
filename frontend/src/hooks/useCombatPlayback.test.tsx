import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useCombatPlayback } from './useCombatPlayback'
import { getTimedGameState, playbackPosition } from '../combat/playbackClock'
import { getState } from '../api/gameApi'
import type { GameState } from '../types'
import type { CombatRecording } from '../types/combat'

vi.mock('../api/gameApi', () => ({ getState: vi.fn() }))
const origin = Date.parse('2026-10-05T12:00:00Z')
const timing = { roundNumber: 1, startsAt: new Date(origin + 1000).toISOString(), combatEndsAt: new Date(origin + 2000).toISOString(), endsAt: new Date(origin + 4000).toISOString(), tickDurationMs: 250 }
const state = { gameId: 'g', currentRound: 1, state: 'ROUND_RESULT', combatPresentation: timing,
  clockSample: { serverAtReceipt: origin, receivedAt: 0 } } as GameState
const recording: CombatRecording = { gameId: 'g', roundNumber: 1, events: [
  { sequenceNumber: 1, tick: 0, type: 'UNIT_PLACED', data: { unitId: 'a', unitType: 'Knight', playerId: 0, level: 1, x: 0, y: 0, currentHp: 18, maxHp: 18 } },
  { sequenceNumber: 2, tick: 0, type: 'UNIT_PLACED', data: { unitId: 'b', unitType: 'Squire', playerId: 1, level: 1, x: 0, y: 4, currentHp: 8, maxHp: 8 } },
  { sequenceNumber: 3, tick: 2, type: 'UNIT_MOVED', data: { unitId: 'a', playerId: 0, x: 0, y: 1 } },
  { sequenceNumber: 4, tick: 2, type: 'ATTACK', data: { attackerId: 'a', targetId: 'b', damage: 5 } },
  { sequenceNumber: 5, tick: 4, type: 'ATTACK', data: { attackerId: 'a', targetId: 'b', damage: 5 } },
  { sequenceNumber: 6, tick: 4, type: 'UNIT_DIED', data: { unitId: 'b' } },
  { sequenceNumber: 7, tick: 4, type: 'COMBAT_ENDED', data: { reason: 'PLAYER_VICTORY' } },
] }
let now = 0
beforeEach(() => { vi.useFakeTimers(); now = 0; vi.spyOn(performance, 'now').mockImplementation(() => now) })
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.useRealTimers() })
function setup(initialState = state, initialRecording: CombatRecording | undefined = recording) {
  return renderHook(({ live, data }: { live: GameState; data: CombatRecording | undefined }) => useCombatPlayback(live, data), { initialProps: { live: initialState, data: initialRecording as CombatRecording | undefined } })
}
function advance(time: number) { now = time; act(() => vi.advanceTimersByTime(32)) }

it('samples server time at the request midpoint despite local wall-clock skew', async () => {
  vi.mocked(getState).mockImplementation(async () => { now = 200; return { ...state, serverTime: new Date(origin).toISOString() } })
  vi.setSystemTime(new Date('2040-01-01'))
  const sampled = await getTimedGameState('g')
  expect(sampled.clockSample).toEqual({ serverAtReceipt: origin + 100, receivedAt: 200 })
})
it('shows placements in lead-in, empty ticks, same-tick actions, final state and result interval', () => {
  const hook = setup()
  expect(hook.result.current.position?.phase).toBe('lead-in')
  expect(hook.result.current.frame?.units.b.currentHp).toBe(8)
  advance(1250)
  expect(hook.result.current.position?.tick).toBe(1)
  expect(hook.result.current.frame?.units.a.y).toBe(0)
  advance(1500)
  expect(hook.result.current.frame?.units.a.y).toBe(1)
  expect(hook.result.current.frame?.units.b.currentHp).toBe(3)
  advance(2000)
  expect(hook.result.current.position?.phase).toBe('result')
  expect(hook.result.current.frame?.units.b.dead).toBe(true)
  expect(hook.result.current.presenting).toBe(false)
  advance(4000)
  expect(hook.result.current.position?.phase).toBe('ended')
  expect(vi.getTimerCount()).toBe(0)
  expect(hook.result.current.frame?.outcome).toBe('PLAYER_VICTORY')
})
it('joins at the current tick after delayed event loading or reload', () => {
  now = 1750
  const hook = setup()
  hook.rerender({ live: state, data: undefined })
  expect(hook.result.current.frame).toBeNull()
  expect(hook.result.current.presenting).toBe(true)
  hook.rerender({ live: state, data: recording })
  expect(hook.result.current.position?.tick).toBe(3)
  expect(hook.result.current.frame?.units.b.currentHp).toBe(3)
  hook.unmount()
  const reloaded = setup()
  expect(reloaded.result.current.frame?.units.b.currentHp).toBe(3)
})
it('skips elapsed ticks on visibility return and ignores local wall-clock changes', () => {
  const hook = setup()
  vi.setSystemTime(new Date('2050-01-01'))
  now = 1750
  act(() => document.dispatchEvent(new Event('visibilitychange')))
  expect(hook.result.current.position?.tick).toBe(3)
  now = 5000
  act(() => window.dispatchEvent(new Event('focus')))
  expect(hook.result.current.position?.phase).toBe('ended')
  expect(vi.getTimerCount()).toBe(0)
})
it('clears playback on preparation, another game, and unmount', () => {
  const hook = setup()
  hook.rerender({ live: { ...state, state: 'PREPARATION', currentRound: 2 }, data: recording })
  expect(hook.result.current.frame).toBeNull()
  expect(vi.getTimerCount()).toBe(0)
  hook.rerender({ live: { ...state, gameId: 'other' }, data: recording })
  expect(hook.result.current.frame).toBeNull()
  hook.unmount()
  expect(vi.getTimerCount()).toBe(0)
})
it('uses an updated server sample without resetting the round timeline', () => {
  const hook = setup()
  hook.rerender({ live: { ...state, clockSample: { receivedAt: 0, serverAtReceipt: origin + 1750 } }, data: recording })
  expect(hook.result.current.position?.tick).toBe(3)
  expect(hook.result.current.frame?.units.b.currentHp).toBe(3)
})
it('does not invent a playback origin for legacy timing or malformed recordings', () => {
  const hook = setup({ ...state, clockSample: undefined })
  expect(hook.result.current.frame).toBeNull()
  expect(vi.getTimerCount()).toBe(0)
  hook.rerender({ live: state, data: { ...recording, events: [] } })
  expect(hook.result.current.error).toBeInstanceOf(Error)
  expect(hook.result.current.frame).toBeNull()
})
it('validates timing and supports tick-zero fights', () => {
  expect(() => playbackPosition({ ...timing, tickDurationMs: 0 }, origin)).toThrow()
  const empty = { ...timing, combatEndsAt: timing.startsAt }
  expect(playbackPosition(empty, origin + 1000)).toMatchObject({ phase: 'result', tick: 0 })
})

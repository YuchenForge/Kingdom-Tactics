import { act, cleanup, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useGameState } from './useGameState'
import { ApiError } from '../api/client'
import * as api from '../api/gameApi'
import type { CommandResult, GameState, RoundResult } from '../types'

const { navigate, expire } = vi.hoisted(() => ({ navigate: vi.fn(), expire: vi.fn() }))
vi.mock('react-router', () => ({ useNavigate: () => navigate }))
vi.mock('./useAuth', () => ({ useAuth: () => ({ expire }) }))
vi.mock('../api/gameApi', async (original) => ({
  ...await original<typeof import('../api/gameApi')>(), getState: vi.fn(), getRoundResult: vi.fn(), getCombatEventsPage: vi.fn(),
}))
const state: GameState = {
  gameId: 'g', state: 'PREPARATION', currentRound: 1, latestResolvedRound: null,
  yourSeat: 0, yourGold: 10, yourKeepHp: 20, opponentKeepHp: 20, opponentUnitCount: 0,
  yourBoard: [], yourUnits: {}, yourLane: [], shop: [], isLocked: false, opponentIsLocked: false,
  planningDeadline: '2026-09-30T12:00:00Z',
}
const outcome: RoundResult = { roundNumber: 1, outcome: 'DRAW', keepDamage: { '0': 0, '1': 0 }, keepHpAfter: { '0': 20, '1': 20 }, endSnapshots: {} }
const command: CommandResult = { gameId: 'g', roundNumber: 1, success: true, gold: 7, board: [], lane: [], units: {}, shop: [], isLocked: false }
let client: QueryClient
function setup(id: string | undefined = 'g') {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  return renderHook(({ gameId }) => useGameState(gameId), { initialProps: { gameId: id }, wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> })
}
async function tick(ms = 20) { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks()
  vi.mocked(api.getState).mockReset().mockResolvedValue(state)
  vi.mocked(api.getRoundResult).mockReset().mockResolvedValue(outcome)
})
afterEach(() => { cleanup(); client?.clear(); vi.useRealTimers() })

it('polls through every active phase and fetches results during next preparation', async () => {
  const hook = setup(); await tick()
  expect(hook.result.current.canMutate).toBe(true)
  for (const phase of ['LOCKED', 'RESOLVING', 'ROUND_RESULT'] as const) {
    vi.mocked(api.getState).mockResolvedValue({ ...state, state: phase })
    await tick(1020)
    expect(hook.result.current.phase).toBe(phase)
    expect(hook.result.current.canMutate).toBe(false)
  }
  vi.mocked(api.getState).mockResolvedValue({ ...state, currentRound: 2, latestResolvedRound: 1 })
  await tick(1020)
  expect(api.getRoundResult).toHaveBeenCalledWith('g', 1)
  expect(hook.result.current.roundResult).toEqual(outcome)
  expect(hook.result.current.showDeadline).toBe(true)
  expect(hook.result.current.canMutate).toBe(true)
  const calls = vi.mocked(api.getState).mock.calls.length
  await tick(1020)
  expect(vi.mocked(api.getState).mock.calls.length).toBeGreaterThan(calls)
  expect(api.getRoundResult).toHaveBeenCalledTimes(1)
  vi.mocked(api.getState).mockResolvedValue({ ...state, currentRound: 3, latestResolvedRound: 2 })
  vi.mocked(api.getRoundResult).mockResolvedValue({ ...outcome, roundNumber: 2 })
  await tick(1020)
  expect(api.getRoundResult).toHaveBeenLastCalledWith('g', 2)
  expect(client.getQueryData(['roundResult', 'g', 1])).toEqual(outcome)
})
it('retains the last snapshot but disables mutations until a successful poll', async () => {
  const hook = setup(); await tick()
  vi.mocked(api.getState).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', 'Offline'))
  await tick(1020)
  expect(hook.result.current.state).toEqual(state)
  expect(hook.result.current.isReconnecting).toBe(true)
  expect(hook.result.current.canMutate).toBe(false)
  vi.mocked(api.getState).mockResolvedValue(state)
  await tick(1020)
  expect(hook.result.current.isReconnecting).toBe(false)
  expect(hook.result.current.canMutate).toBe(true)
})
it.each([401, 403, 404])('stops polling after hard failure %s', async (status) => {
  vi.mocked(api.getState).mockRejectedValue(new ApiError(status, 'ERROR', 'Denied'))
  const hook = setup(); await tick(); await tick(3000)
  expect(api.getState).toHaveBeenCalledTimes(1)
  expect(hook.result.current.canMutate).toBe(false)
  expect(expire).toHaveBeenCalledTimes(status === 401 ? 1 : 0)
})
it.each(['FINISHED', 'WAITING_FOR_PLAYERS'] as const)('navigates out of %s and stops polling', async (phase) => {
  vi.mocked(api.getState).mockResolvedValue({ ...state, state: phase })
  setup(); await tick(); await tick(3000)
  expect(navigate).toHaveBeenCalledWith(phase === 'FINISHED' ? '/games/g/result' : '/games/g', { replace: true })
  expect(api.getState).toHaveBeenCalledTimes(1)
})
it('returns GAME_NOT_READY to lobby', async () => {
  vi.mocked(api.getState).mockRejectedValue(new ApiError(409, 'GAME_NOT_READY', 'Waiting'))
  setup(); await tick()
  expect(navigate).toHaveBeenCalledWith('/games/g', { replace: true })
})
it('does not fetch or enable commands for a missing id or initial load', async () => {
  const hook = setup(''); await tick()
  expect(api.getState).not.toHaveBeenCalled()
  expect(hook.result.current.canMutate).toBe(false)
  vi.mocked(api.getState).mockReturnValue(new Promise(() => {}))
  hook.rerender({ gameId: 'g' }); await tick()
  expect(hook.result.current.isLoading).toBe(true)
  expect(hook.result.current.canMutate).toBe(false)
})
it('uses the current cache to discard stale replies without overwriting it', async () => {
  const hook = setup(); await tick()
  const handler = hook.result.current.onCommandSuccess
  act(() => { client.setQueryData(['gameState', 'g'], { ...state, currentRound: 2 }) })
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  expect(await handler(command)).toBe(false)
  expect(await handler({ ...command, gameId: 'other', roundNumber: 2 })).toBe(false)
  expect(invalidate).not.toHaveBeenCalled()
  expect(client.getQueryData<GameState>(['gameState', 'g'])?.currentRound).toBe(2)
  vi.mocked(api.getState).mockResolvedValue({ ...state, currentRound: 2 })
  await act(async () => { expect(await handler({ ...command, roundNumber: 2 })).toBe(true) })
  expect(invalidate).toHaveBeenCalledWith({ queryKey: ['gameState', 'g'], exact: true })
})
it('refetches only for WRONG_GAME_STATE and ignores old route callbacks', async () => {
  const hook = setup(); await tick()
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  await act(async () => { expect(await hook.result.current.onCommandError(new ApiError(409, 'WRONG_GAME_STATE', 'Advanced'))).toBe(true) })
  expect(invalidate).toHaveBeenCalledTimes(1)
  expect(await hook.result.current.onCommandError(new ApiError(409, 'CONFLICT', 'Occupied'))).toBe(false)
  const oldHandler = hook.result.current.onCommandSuccess
  hook.rerender({ gameId: 'other' }); await tick()
  expect(await oldHandler(command)).toBe(false)
  expect(invalidate).toHaveBeenCalledTimes(1)
})
it('keeps polling when a round result fails and keeps locked planning read-only', async () => {
  vi.mocked(api.getState).mockResolvedValue({ ...state, latestResolvedRound: 1, isLocked: true })
  vi.mocked(api.getRoundResult).mockRejectedValue(new ApiError(404, 'NOT_FOUND', 'No result'))
  const hook = setup(); await tick(); await tick(1020)
  expect(hook.result.current.roundResultError).toBeTruthy()
  expect(hook.result.current.canMutate).toBe(false)
  expect(vi.mocked(api.getState).mock.calls.length).toBeGreaterThan(1)
})

it('discovers a resolved recording without observing resolving and cancels on preparation', async () => {
  vi.mocked(api.getState).mockResolvedValue({ ...state, state: 'ROUND_RESULT', latestResolvedRound: 1 })
  vi.mocked(api.getCombatEventsPage).mockResolvedValue({ roundNumber: 1, events: [], complete: true, hasMore: false, nextAfterSequence: 0 })
  const hook = setup(); await tick(50)
  expect(hook.result.current.combatRecording?.roundNumber).toBe(1)
  vi.mocked(api.getState).mockResolvedValue({ ...state, currentRound: 2, latestResolvedRound: 1 })
  await tick(1100)
  expect(hook.result.current.combatRecording).toBeUndefined()
  expect(hook.result.current.roundResult).toEqual(outcome)
})

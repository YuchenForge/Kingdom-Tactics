import { act, cleanup, renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useCombatEvents } from './useCombatEvents'
import { getCombatEventsPage } from '../api/gameApi'
import { ApiError } from '../api/client'
import type { CombatEvent, CombatEventsPage } from '../types/combat'

vi.mock('../api/gameApi', () => ({ getCombatEventsPage: vi.fn() }))
const event = (sequenceNumber: number): CombatEvent => ({ sequenceNumber, tick: 1, type: 'UNIT_DIED', data: { unitId: `u${sequenceNumber}` } })
const page = (events: CombatEvent[], hasMore = false, complete = true, roundNumber = 1): CombatEventsPage => ({ events, hasMore, complete, roundNumber, nextAfterSequence: events.at(-1)?.sequenceNumber ?? 0 })
let client: QueryClient
function setup() {
  client = new QueryClient({ defaultOptions: { queries: { gcTime: Infinity } } })
  return renderHook(({ gameId, round }: { gameId: string; round: number | null }) => useCombatEvents(gameId, round), {
    initialProps: { gameId: 'g', round: 1 as number | null },
    wrapper: ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
  })
}
async function tick(ms = 20) { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }
beforeEach(() => { vi.useFakeTimers(); vi.mocked(getCombatEventsPage).mockReset() })
afterEach(() => { cleanup(); client?.clear(); vi.useRealTimers() })

it('loads every page, deduplicates, and caches only the complete sequence', async () => {
  vi.mocked(getCombatEventsPage).mockResolvedValueOnce(page([event(1), event(2)], true))
    .mockResolvedValueOnce(page([event(2), event(3)]))
  const hook = setup(); await tick()
  expect(hook.result.current.data?.events.map(e => e.sequenceNumber)).toEqual([1, 2, 3])
  expect(getCombatEventsPage).toHaveBeenNthCalledWith(2, 'g', 1, 2, expect.any(AbortSignal))
  hook.rerender({ gameId: 'g', round: null }); await tick()
  expect(hook.result.current.data).toBeUndefined()
  hook.rerender({ gameId: 'g', round: 1 }); await tick()
  expect(getCombatEventsPage).toHaveBeenCalledTimes(2)
})
it('waits for incomplete empty responses and retries the failed page at the same cursor', async () => {
  vi.mocked(getCombatEventsPage).mockResolvedValueOnce(page([], false, false))
    .mockResolvedValueOnce(page([event(1)], true))
    .mockRejectedValueOnce(new ApiError(503, 'BUSY', 'Busy'))
    .mockResolvedValueOnce(page([event(2)]))
  const hook = setup(); await tick()
  expect(hook.result.current.data).toBeUndefined()
  await tick(1000)
  expect(hook.result.current.data).toBeUndefined()
  await tick(1000)
  expect(hook.result.current.data?.events).toHaveLength(2)
  expect(vi.mocked(getCombatEventsPage).mock.calls.map(c => c[2])).toEqual([0, 0, 1, 1])
})
it.each([401, 403, 404])('does not retry authorization/not-found failure %s', async status => {
  vi.mocked(getCombatEventsPage).mockRejectedValue(new ApiError(status, 'DENIED', 'Denied'))
  const hook = setup(); await tick(10000)
  expect(hook.result.current.isError).toBe(true)
  expect(getCombatEventsPage).toHaveBeenCalledTimes(1)
})
it('bounds transient retries and supports manual retry', async () => {
  vi.mocked(getCombatEventsPage).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', 'Offline'))
  const hook = setup(); await tick(8000)
  expect(getCombatEventsPage).toHaveBeenCalledTimes(4)
  expect(hook.result.current.isError).toBe(true)
  vi.mocked(getCombatEventsPage).mockResolvedValue(page([event(1)]))
  await act(async () => { await hook.result.current.refetch() }); await tick()
  expect(hook.result.current.data?.events).toHaveLength(1)
})
it.each(['round', 'game', 'disable', 'unmount'])('cancels and ignores late responses on %s change', async change => {
  let finish!: (value: CombatEventsPage) => void
  vi.mocked(getCombatEventsPage).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    .mockResolvedValue(page([event(1)], false, true, change === 'round' ? 2 : 1))
  const hook = setup(); await tick()
  const signal = vi.mocked(getCombatEventsPage).mock.calls[0][3]!
  if (change === 'unmount') hook.unmount()
  else hook.rerender({ gameId: change === 'game' ? 'other' : 'g', round: change === 'disable' ? null : change === 'round' ? 2 : 1 })
  await tick()
  expect(signal.aborted).toBe(true)
  finish(page([event(1), event(2)])); await tick()
  expect(client.getQueryData(['combatEvents', 'g', 1])).toBeUndefined()
  if (change === 'round' || change === 'game') expect(hook.result.current.data?.events).toHaveLength(1)
})
it.each([
  page([event(2)]),
  { ...page([event(1)], true), nextAfterSequence: 0 },
  page([event(1)], false, true, 2),
])('rejects gaps, invalid cursors and wrong-round data', async response => {
  vi.mocked(getCombatEventsPage).mockResolvedValue(response)
  const hook = setup(); await tick()
  expect(hook.result.current.isError).toBe(true)
  expect(hook.result.current.data).toBeUndefined()
})

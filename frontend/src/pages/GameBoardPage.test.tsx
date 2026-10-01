import '@testing-library/jest-dom/vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import GameBoardPage from './GameBoardPage'
import { useGameState } from '../hooks/useGameState'
import * as api from '../api/gameApi'
import { ApiError } from '../api/client'
import type { GameState, UnitView, CommandResult } from '../types'
vi.mock('../hooks/useGameState', () => ({ useGameState: vi.fn() }))
vi.mock('../hooks/useAuth', () => ({ useAuth: () => ({ logout: vi.fn(), expire: vi.fn() }) }))
vi.mock('../api/gameApi', async (original) => ({ ...await original<typeof import('../api/gameApi')>(), buy: vi.fn(), sell: vi.fn(), relocate: vi.fn(), lock: vi.fn(), refresh: vi.fn() }))
const unit: UnitView = { id: 'u1', unitType: 'Squire', level: 2, maxHp: 40, attack: 8, range: 1, specialAbility: 'None', healAmount: 0, sellRefund: 6 }
let state: GameState
let hook: ReturnType<typeof useGameState>
const response: CommandResult = { gameId: 'g', roundNumber: 1, success: true, gold: 5, lane: [], board: [], units: {}, shop: [], isLocked: false }
function page() { return <MemoryRouter initialEntries={['/games/g/play']}><Routes><Route path="/games/:gameId/play" element={<GameBoardPage />} /></Routes></MemoryRouter> }
function update() { vi.mocked(useGameState).mockReturnValue({ ...hook, state }) }
beforeEach(() => {
  vi.clearAllMocks()
  state = { gameId: 'g', state: 'PREPARATION', currentRound: 1, latestResolvedRound: null, yourSeat: 0, yourGold: 10, yourKeepHp: 20, opponentKeepHp: 20, opponentUnitCount: 0,
    yourBoard: Array.from({ length: 4 }, () => Array(4).fill(null)), yourUnits: { u1: unit }, yourLane: [{ slot: 0, unitId: 'u1', unitType: 'Squire', level: 2 }],
    shop: [{ slot: 0, unitType: 'Squire', cost: 3, maxHp: 20, attack: 4, range: 1, specialAbility: null, healAmount: 0 }], isLocked: false, opponentIsLocked: false, planningDeadline: '2026-10-01T12:00:00Z' }
  hook = { state, canMutate: true, isLoading: false, isReconnecting: false, error: null, phase: 'PREPARATION', roundResult: undefined, roundResultError: null, isResolving: false, showDeadline: true,
    refetch: vi.fn(), invalidate: vi.fn(), onCommandSuccess: vi.fn().mockResolvedValue(true), onCommandError: vi.fn().mockResolvedValue(false) }
  update()
  for (const fn of [api.buy, api.sell, api.relocate, api.lock, api.refresh]) vi.mocked(fn).mockResolvedValue(response)
})
afterEach(cleanup)
it('renders 16 cells, three shop cards, five lane slots and a reloaded leveled unit', () => {
  render(page())
  expect(screen.getAllByRole('gridcell')).toHaveLength(16)
  expect(screen.getAllByRole('button', { name: /Buy slot/ })).toHaveLength(3)
  expect(screen.getAllByRole('button', { name: /Lane slot/ })).toHaveLength(4)
  expect(screen.getByText('Holding lane · 1 / 5')).toBeInTheDocument()
  expect(screen.getByText('Deployed · 0 / 3')).toBeInTheDocument()
  expect(screen.getByText('Level 2')).toBeInTheDocument()
})
it('Inspect opens details, selection closes them, and empty legal cells highlight', () => {
  render(page())
  fireEvent.click(screen.getByRole('button', { name: 'Inspect Squire u1' }))
  expect(screen.getByRole('dialog')).toHaveTextContent('Sell refund: 6 gold')
  fireEvent.click(screen.getByRole('button', { name: 'Select Squire u1' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Select Squire u1' })).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: 'Board x 1 y 3' })).toHaveClass('legal')
})
it('mouse hover opens the popup and selection closes it', () => {
  render(page())
  const tile = screen.getByRole('button', { name: 'Select Squire u1' }).parentElement!
  const hover = new Event('pointerover', { bubbles: true }); Object.defineProperty(hover, 'pointerType', { value: 'mouse' })
  fireEvent(tile, hover)
  expect(screen.getByRole('dialog')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Select Squire u1' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})
it('relocates with unswapped coordinates and no gold charge precondition', async () => {
  state.yourGold = 0; update(); render(page())
  fireEvent.click(screen.getByRole('button', { name: 'Select Squire u1' }))
  fireEvent.click(screen.getByRole('button', { name: 'Board x 1 y 3' }))
  expect(api.relocate).toHaveBeenCalledWith('g', 1, { unitId: 'u1', to: { type: 'BOARD', x: 1, y: 3 } }, expect.any(String))
  await screen.findByText('Moving costs no gold.', { exact: false })
})
it('explains insufficient gold and sold offers', () => {
  state.yourGold = 0; update(); render(page())
  expect(screen.getByRole('button', { name: 'Buy slot 1' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Buy slot 1' })).toHaveAccessibleDescription('Not enough gold')
  expect(screen.getByRole('button', { name: 'Buy slot 2' })).toHaveAccessibleDescription('Offer already sold')
})
it('enforces board cap for lane units, allows board moves, and raises cap in round 5', () => {
  state.yourBoard[0] = ['b1', 'b2', 'b3', null]
  for (const id of ['b1', 'b2', 'b3']) state.yourUnits[id] = { ...unit, id }
  update(); const view = render(page())
  fireEvent.click(screen.getByRole('button', { name: 'Select Squire u1' }))
  expect(screen.getByRole('button', { name: 'Board x 3 y 0' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Select Squire b1' }))
  expect(screen.getByRole('button', { name: 'Board x 3 y 0' })).toBeEnabled()
  state = { ...state, currentRound: 5 }; update(); view.rerender(page())
  expect(screen.getByText('Deployed · 3 / 5')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Select Squire b1' })).toHaveAttribute('aria-pressed', 'false')
})
it('confirms lock and clears selection after success', async () => {
  render(page()); fireEvent.click(screen.getByRole('button', { name: 'Select Squire u1' }))
  fireEvent.click(screen.getByRole('button', { name: 'Lock board' }))
  expect(api.lock).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Confirm lock' }))
  expect(api.lock).toHaveBeenCalledTimes(1)
  await screen.findByText('Select a unit, then choose an empty destination.')
})
it('blocks commands while locked or reconnecting', () => {
  state.isLocked = true; hook.canMutate = false; update(); const view = render(page())
  expect(screen.getByRole('button', { name: 'Lock board' })).toBeDisabled()
  state.isLocked = false; hook.isReconnecting = true; update(); view.rerender(page())
  expect(screen.getByRole('button', { name: 'Buy slot 1' })).toBeDisabled()
  expect(screen.getByRole('button', { name: 'Refresh shop · 1 gold' })).toBeDisabled()
})
it('prevents duplicate submissions and reuses the same key on retry', async () => {
  vi.mocked(api.buy).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'Offline'))
  render(page()); fireEvent.click(screen.getByRole('button', { name: 'Buy slot 1' }))
  expect(screen.getByRole('button', { name: 'Buy slot 1' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: 'Buy slot 1' }))
  const retry = await screen.findByRole('button', { name: 'Retry same action' })
  expect(api.buy).toHaveBeenCalledTimes(1)
  fireEvent.click(retry)
  expect(api.buy).toHaveBeenCalledTimes(2)
  expect(vi.mocked(api.buy).mock.calls[0]).toEqual(vi.mocked(api.buy).mock.calls[1])
})
it('clears selection when merging changes its level and sells only from details', async () => {
  const view = render(page()); fireEvent.click(screen.getByRole('button', { name: 'Select Squire u1' }))
  state = { ...state, yourUnits: { u1: { ...unit, level: 3 } } }; update(); view.rerender(page())
  expect(screen.getByRole('button', { name: 'Select Squire u1' })).toHaveAttribute('aria-pressed', 'false')
  expect(screen.queryByRole('button', { name: /Sell for/ })).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Inspect Squire u1' }))
  fireEvent.click(screen.getByRole('button', { name: 'Sell for 6 gold' }))
  expect(api.sell).toHaveBeenCalledWith('g', 1, 'u1', expect.any(String))
  await screen.findByRole('button', { name: 'Lock board' })
})

it('moves to the exact lane slot and blocks buys with a full lane', async () => {
  const view = render(page())
  fireEvent.click(screen.getByRole('button', { name: 'Select Squire u1' }))
  fireEvent.click(screen.getByRole('button', { name: 'Lane slot 5' }))
  expect(api.relocate).toHaveBeenCalledWith('g', 1, { unitId: 'u1', to: { type: 'LANE', slot: 4 } }, expect.any(String))
  await screen.findByRole('button', { name: 'Lock board' })
  state = { ...state, yourLane: Array.from({ length: 5 }, (_, slot) => ({ slot, unitId: `u${slot + 1}`, unitType: 'Squire', level: 2 })) }
  update(); view.rerender(page())
  expect(screen.getByText('Holding lane · 5 / 5')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Buy slot 1' })).toBeDisabled()
})
it('delegates WRONG_GAME_STATE without offering a retry or resubmitting', async () => {
  const failure = new ApiError(409, 'WRONG_GAME_STATE', 'Round advanced')
  vi.mocked(api.buy).mockRejectedValueOnce(failure)
  render(page()); fireEvent.click(screen.getByRole('button', { name: 'Buy slot 1' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Round advanced')
  expect(hook.onCommandError).toHaveBeenCalledWith(failure)
  expect(screen.queryByRole('button', { name: 'Retry same action' })).not.toBeInTheDocument()
  expect(api.buy).toHaveBeenCalledTimes(1)
})
it('clears selection when the selected id disappears after merging', () => {
  const view = render(page()); fireEvent.click(screen.getByRole('button', { name: 'Select Squire u1' }))
  state = { ...state, yourUnits: {}, yourLane: [] }; update(); view.rerender(page())
  expect(screen.getByRole('button', { name: 'Board x 1 y 3' })).toBeDisabled()
  expect(screen.getByText('Select a unit, then choose an empty destination.')).toBeInTheDocument()
})

it('switches to both combat formations and removes the merged field on the next preparation', () => {
  const view = render(page())
  state = { ...state, state: 'RESOLVING', combatUnits: [
    { id: 'a', type: 'Squire', level: 2, seat: 0, x: 2, y: 1 },
    { id: 'b', type: 'Archer', level: 1, seat: 1, x: 2, y: 5 },
  ] }
  update(); view.rerender(page())
  expect(screen.getAllByRole('gridcell')).toHaveLength(32)
  expect(screen.getByRole('gridcell', { name: 'Global 2,1: Squire, level 2, friendly' })).toBeInTheDocument()
  expect(screen.getByRole('gridcell', { name: 'Global 2,5: Archer, level 1, enemy' })).toBeInTheDocument()
  state = { ...state, state: 'PREPARATION', currentRound: 2, combatUnits: [] }
  update(); view.rerender(page())
  expect(screen.queryByRole('region', { name: 'Merged combat board' })).not.toBeInTheDocument()
  expect(screen.getAllByRole('gridcell')).toHaveLength(16)
})

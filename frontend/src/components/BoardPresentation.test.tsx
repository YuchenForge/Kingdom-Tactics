import '@testing-library/jest-dom/vitest'
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import PlanningTimer from './PlanningTimer'
import CombatBoard from './CombatBoard'
import UnitPopup from './UnitPopup'
import type { GameState } from '../types'

afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks() })
it('counts down from the server deadline and waits at zero', () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-01T12:00:00Z'))
  render(<PlanningTimer deadline="2026-10-01T12:00:45Z" />)
  expect(screen.getByRole('timer')).toHaveTextContent('00:45')
  act(() => vi.advanceTimersByTime(12000))
  expect(screen.getByRole('timer')).toHaveTextContent('00:33')
  act(() => vi.advanceTimersByTime(40000))
  expect(screen.getByRole('timer')).toHaveTextContent('00:00 · Waiting for server')
})
const state = { currentRound: 1, yourSeat: 0, yourKeepHp: 20, opponentKeepHp: 20 } as GameState
state.combatUnits = [{ id: 'u', type: 'Squire', level: 2, seat: 0, x: 3, y: 0 }]
it('renders a merged 32-cell field while awaiting the server', () => {
  render(<CombatBoard state={state} />)
  expect(screen.getAllByRole('gridcell')).toHaveLength(32)
  expect(screen.getByRole('status')).toHaveTextContent('Resolving')
})
it('orients global server positions for each seat', () => {
  const view = render(<CombatBoard state={state} />)
  expect(screen.getAllByRole('gridcell')[0]).toHaveAccessibleName('Global 3,0: Squire, level 2, friendly')
  view.rerender(<CombatBoard state={{ ...state, yourSeat: 1 }} />)
  expect(screen.getAllByRole('gridcell')[31]).toHaveAccessibleName('Global 3,0: Squire, level 2, enemy')
})
it('portals details outside clipping containers and flips above a low anchor', () => {
  const anchor = document.createElement('div')
  vi.spyOn(anchor, 'getBoundingClientRect').mockReturnValue({ left: 900, top: 700, bottom: 740 } as DOMRect)
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    return { width: 300, height: 200 } as DOMRect
  })
  const view = render(<UnitPopup anchor={{ current: anchor }} label="Details" onEnter={() => {}} onLeave={() => {}} onClose={() => {}}>Sell</UnitPopup>)
  const popup = screen.getByRole('dialog')
  expect(view.container).not.toContainElement(popup)
  expect(popup.style.top).toBe('496px')
  expect(parseFloat(popup.style.left) + 300).toBeLessThanOrEqual(window.innerWidth - 12)
})

import '@testing-library/jest-dom/vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import AnimatedCombatBoard from './AnimatedCombatBoard'
import { buildCombatMotion } from '../combat/combatMotion'
import { reconstructCombat } from '../combat/combatState'
import type { GameState } from '../types'
import type { CombatEvent } from '../types/combat'
const events: CombatEvent[] = [
  { sequenceNumber: 1, tick: 0, type: 'UNIT_PLACED', data: { unitId: 'a', unitType: 'Ranger', playerId: 0, level: 1, x: 3, y: 1, currentHp: 7, maxHp: 7 } },
  { sequenceNumber: 2, tick: 0, type: 'UNIT_PLACED', data: { unitId: 'b', unitType: 'Squire', playerId: 1, level: 1, x: 3, y: 4, currentHp: 8, maxHp: 8 } },
  { sequenceNumber: 3, tick: 4, type: 'ATTACK', data: { attackerId: 'a', targetId: 'b', damage: 4 } },
  { sequenceNumber: 4, tick: 8, type: 'COMBAT_ENDED', data: { reason: 'TIME_LIMIT' } },
]
const motion = buildCombatMotion(reconstructCombat({ gameId: 'g', roundNumber: 1, events }), 250)
const state = { gameId: 'g', currentRound: 1, yourSeat: 0, yourKeepHp: 30, opponentKeepHp: 30 } as GameState
const board = (elapsed: number, merge = 1, seat = 0) => <AnimatedCombatBoard state={{ ...state, yourSeat: seat }} motion={motion} elapsed={elapsed} merge={merge} resultReady={false} />
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
it('shows projectile travel before changing HP or showing damage, then samples the impact', () => {
  const view = render(board(1150))
  expect(screen.getByRole('meter', { name: 'Squire health' })).toHaveAttribute('aria-valuenow', '8')
  expect(view.container.querySelectorAll('.combat-effects rect').length).toBeGreaterThan(0)
  expect(screen.queryByText('−4')).not.toBeInTheDocument()
  view.rerender(board(1190))
  expect(screen.getByRole('meter', { name: 'Squire health' })).toHaveAttribute('aria-valuenow', '4')
  expect(screen.getByText('−4')).toBeInTheDocument()
  view.rerender(board(1800))
  expect(screen.queryByText('−4')).not.toBeInTheDocument()
})
it('keeps portraits upright during the measured board merge for both seats', () => {
  const view = render(board(-500, .5))
  expect(screen.getAllByRole('gridcell')).toHaveLength(32)
  expect(view.container.querySelector('.combat-cell')?.getAttribute('style')).toContain('rotate(')
  expect(view.container.querySelector('.combat-sprite')?.getAttribute('style')).not.toContain('rotate(')
  view.rerender(board(0, 1, 1))
  expect(view.container.querySelector('[data-unit="a"]')).toHaveClass('enemy')
})
it('respects reduced motion without removing damage feedback', () => {
  vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  const view = render(board(1150, .5))
  expect(view.container.querySelector('.animated-grid')).not.toHaveClass('merging')
  expect(view.container.querySelector('.combat-action')?.getAttribute('style') ?? '').not.toContain('translate')
  expect(view.container.querySelectorAll('.combat-effects circle')).toHaveLength(0)
  view.rerender(board(1190))
  expect(screen.getByText('−4')).toBeInTheDocument()
})

it('starts from a single planning formation, shrinks it, and only then flips in the opponent', () => {
  const view = render(board(-2200, 0))
  const own = view.container.querySelector('[data-unit="a"]')!
  const enemy = view.container.querySelector('[data-unit="b"]')!
  expect(own.getAttribute('style')).toContain('scale(1.25)')
  expect(enemy).toHaveStyle({ opacity: '0' })
  view.rerender(board(-1000, 0))
  expect(own.getAttribute('style')).toContain('scale(1)')
  expect(enemy).toHaveStyle({ opacity: '0' })
  view.rerender(board(-500, .5))
  expect(enemy).toHaveStyle({ opacity: '1' })
  view.rerender(board(0, 1))
  expect(view.container.querySelector('.combat-cell')?.getAttribute('style')).toContain('rotate(0rad)')
})

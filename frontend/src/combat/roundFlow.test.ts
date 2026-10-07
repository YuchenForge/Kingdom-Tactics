import { expect, it } from 'vitest'
import { roundOutcomeLabel, roundResultFrame } from './roundFlow'
import { combatSurvivors, matchesRoundResult, reconstructCombat } from './combatState'
import type { CombatEvent } from '../types/combat'
import type { RoundResult } from '../types'
import fixture from './engineRecording.json'

const recordedResult: RoundResult = {
  roundNumber: 1, outcome: 'ENEMY_VICTORY', keepDamage: { '0': 6, '1': 0 }, keepHpAfter: { '0': 24, '1': 30 },
  endSnapshots: { '0': { keepHp: 24, survivors: [] }, '1': { keepHp: 30, survivors: fixture.survivors.map(u => {
    const placement = (fixture.events as CombatEvent[]).find(e => e.type === 'UNIT_PLACED' && e.data.unitId === u.id)!
    if (placement.type !== 'UNIT_PLACED') throw new Error('Missing placement')
    return { id: u.id, x: u.x, y: u.y, currentHp: u.hp, type: placement.data.unitType, level: placement.data.level, maxHp: placement.data.maxHp }
  }) } },
}
it('shows exactly the saved engine survivors and Keep result for both seats', () => {
  const timeline = reconstructCombat({ gameId: 'g', roundNumber: 1, events: fixture.events as CombatEvent[] })
  expect(matchesRoundResult(timeline.final, recordedResult)).toBe(true)
  const frame = roundResultFrame('g', recordedResult)!
  for (const seat of [0, 1]) expect(combatSurvivors(frame, seat)).toEqual(combatSurvivors(timeline.final, seat))
  expect(fixture.keepDamage).toEqual([recordedResult.keepDamage['0'], recordedResult.keepDamage['1']])
})
it('handles an empty-board draw without inventing survivors and rejects missing snapshots', () => {
  const empty = { ...recordedResult, outcome: 'DRAW' as const, endSnapshots: { '0': { keepHp: 30, survivors: [] }, '1': { keepHp: 30, survivors: [] } } }
  expect(roundResultFrame('g', empty)?.units).toEqual({})
  expect(roundResultFrame('g', { ...empty, endSnapshots: {} })).toBeNull()
})
it.each([
  ['PLAYER_VICTORY', 0, 'You won the round'], ['PLAYER_VICTORY', 1, 'Opponent won the round'],
  ['ENEMY_VICTORY', 1, 'You won the round'], ['ENEMY_VICTORY', 0, 'Opponent won the round'],
  ['DRAW', 0, 'Draw'], ['TIME_LIMIT', 1, 'Time limit reached'],
] as const)('labels %s for seat %s', (outcome, seat, label) => expect(roundOutcomeLabel(outcome, seat)).toBe(label))

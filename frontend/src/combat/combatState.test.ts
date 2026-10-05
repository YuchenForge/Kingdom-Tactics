import { expect, it } from 'vitest'
import { combatStateAtTick, combatSurvivors, initialCombatState, matchesRoundResult, reconstructCombat, reduceCombatEvent } from './combatState'
import type { CombatEvent } from '../types/combat'
import type { RoundResult } from '../types'
import fixture from './engineRecording.json'

const placed = (id: string, seat = 0): CombatEvent => ({ sequenceNumber: seat + 1, tick: 0, type: 'UNIT_PLACED',
  data: { unitId: id, unitType: 'Squire', playerId: seat, level: 1, currentHp: 8, maxHp: 8, x: seat, y: seat * 4 } })
function timeline(events: CombatEvent[]) { return reconstructCombat({ gameId: 'g', roundNumber: 1, events }) }

it('reconstructs the engine-generated preview recording to its exact survivors, coordinates and HP', () => {
  // Copied unchanged from the approved Java-engine-generated animation preview fixture.
  const recording = { gameId: 'fixture', roundNumber: 1, events: fixture.events as CombatEvent[] }
  const original = JSON.stringify(recording)
  const result = reconstructCombat(recording)
  const survivors = [0, 1].flatMap(seat => combatSurvivors(result.final, seat))
  expect(survivors.map(({ id, x, y, currentHp }) => ({ id, x, y, hp: currentHp })).sort((a, b) => a.id.localeCompare(b.id)))
    .toEqual([...fixture.survivors].sort((a, b) => a.id.localeCompare(b.id)))
  expect(result.final.tick).toBe(fixture.finalTick)
  expect(result.final.outcome).toBe('ENEMY_VICTORY')
  expect(JSON.stringify(recording)).toBe(original)
  expect(result.steps[0].after.units['0-Healer'].currentHp).toBe(10)
})
it('retains dead actors for later same-tick attacks and captures each intermediate HP', () => {
  const t = timeline([placed('a'), placed('b', 1),
    { sequenceNumber: 3, tick: 4, type: 'ATTACK', data: { attackerId: 'a', targetId: 'b', damage: 20 } },
    { sequenceNumber: 4, tick: 4, type: 'UNIT_DIED', data: { unitId: 'b' } },
    { sequenceNumber: 5, tick: 4, type: 'ATTACK', data: { attackerId: 'b', targetId: 'a', damage: 3 } },
    { sequenceNumber: 6, tick: 4, type: 'HEALED', data: { healerId: 'a', targetId: 'a', amount: 2 } },
  ])
  expect(t.final.units.a.currentHp).toBe(7)
  expect(t.final.units.b).toMatchObject({ dead: true, currentHp: 0, x: 1, y: 4 })
  expect(t.steps[4].after.units.a.currentHp).toBe(5)
  expect(t.steps[4].before.units.a.currentHp).toBe(8)
  expect(t.steps[2].after.units.b.dead).toBe(false) // death is an explicit recorded event
  expect(combatSurvivors(t.final, 1)).toEqual([])
})
it('uses only recorded splash hits, including zero healing, and clamps health', () => {
  const t = timeline([placed('mage'), placed('b', 1), { ...placed('c'), sequenceNumber: 3 },
    { sequenceNumber: 4, tick: 1, type: 'ATTACK', data: { attackerId: 'mage', targetId: 'b', damage: 2 } },
    { sequenceNumber: 5, tick: 1, type: 'ATTACK', data: { attackerId: 'mage', targetId: 'c', damage: 1 } },
    { sequenceNumber: 6, tick: 1, type: 'HEALED', data: { healerId: 'mage', targetId: 'b', amount: 0 } },
    { sequenceNumber: 7, tick: 2, type: 'HEALED', data: { healerId: 'mage', targetId: 'c', amount: 99 } },
  ])
  expect(t.final.units.mage.currentHp).toBe(8)
  expect(t.final.units.b.currentHp).toBe(6)
  expect(t.final.units.c.currentHp).toBe(8)
})
it('seeks across empty ticks, retains previous positions, and never rotates global coordinates', () => {
  const t = timeline([placed('a'), placed('b', 1),
    { sequenceNumber: 3, tick: 5, type: 'UNIT_MOVED', data: { unitId: 'a', playerId: 0, x: 3, y: 7 } },
    { sequenceNumber: 4, tick: 9, type: 'COMBAT_ENDED', data: { reason: 'TIME_LIMIT' } },
  ])
  expect(combatStateAtTick(t, -1)).toBe(t.initial)
  expect(combatStateAtTick(t, 4).units.a).toMatchObject({ x: 0, y: 0 })
  expect(combatStateAtTick(t, 5).units.a).toMatchObject({ x: 3, y: 7 })
  expect(combatStateAtTick(t, 8).outcome).toBeNull()
  expect(combatStateAtTick(t, 100)).toBe(t.final)
  expect(t.steps[2].before.units.a).toMatchObject({ x: 0, y: 0 })
  expect(t.final.units.b).toMatchObject({ seat: 1, x: 1, y: 4 })
})
it('handles empty fights and compares server snapshots without calculating Keep damage', () => {
  const t = timeline([{ sequenceNumber: 1, tick: 0, type: 'COMBAT_ENDED', data: { reason: 'DRAW' } }])
  const result: RoundResult = { roundNumber: 1, outcome: 'DRAW', keepDamage: { '0': 0, '1': 0 }, keepHpAfter: { '0': 30, '1': 30 },
    endSnapshots: { '0': { survivors: [], keepHp: 30 }, '1': { survivors: [], keepHp: 30 } } }
  expect(matchesRoundResult(t.final, result)).toBe(true)
  expect(matchesRoundResult(t.final, { ...result, roundNumber: 2 })).toBe(false)
  expect(matchesRoundResult(t.final, { ...result, outcome: 'TIME_LIMIT' })).toBe(false)
  expect(matchesRoundResult(t.final, { ...result, endSnapshots: {} })).toBe(false)
})
it('compares every survivor field against independent server data', () => {
  const t = timeline([placed('a'), { sequenceNumber: 2, tick: 1, type: 'COMBAT_ENDED', data: { reason: 'PLAYER_VICTORY' } }])
  const survivor = { id: 'a', type: 'Squire', level: 1, x: 0, y: 0, currentHp: 8, maxHp: 8 }
  const result: RoundResult = { roundNumber: 1, outcome: 'PLAYER_VICTORY', keepDamage: {}, keepHpAfter: {},
    endSnapshots: { '0': { survivors: [survivor], keepHp: 30 }, '1': { survivors: [], keepHp: 28 } } }
  expect(matchesRoundResult(t.final, result)).toBe(true)
  for (const field of ['level', 'x', 'y', 'currentHp', 'maxHp'] as const) {
    const changed = { ...result, endSnapshots: { ...result.endSnapshots, '0': { survivors: [{ ...survivor, [field]: 99 }], keepHp: 30 } } }
    expect(matchesRoundResult(t.final, changed)).toBe(false)
  }
})
it('rejects missing actors and out-of-order events instead of inventing state', () => {
  const initial = initialCombatState('g', 1)
  expect(() => reduceCombatEvent(initial, { ...placed('a'), sequenceNumber: 2 })).toThrow()
  expect(() => reduceCombatEvent(initial, { sequenceNumber: 1, tick: 0, type: 'UNIT_DIED', data: { unitId: 'missing' } })).toThrow()
  expect(() => timeline([placed('a'), { ...placed('a'), sequenceNumber: 2 }])).toThrow()
})

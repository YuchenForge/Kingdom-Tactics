import { expect, it } from 'vitest'
import { reconstructCombat } from './combatState'
import { buildCombatMotion, sampleCombatMotion, displayPoint } from './combatMotion'
import type { CombatEvent } from '../types/combat'
import fixture from './engineRecording.json'

function placed(id: string, type: string, sequenceNumber: number, x = 0, y = 0): CombatEvent {
  return { sequenceNumber, tick: 0, type: 'UNIT_PLACED', data: { unitId: id, unitType: type, level: 1, currentHp: 10, maxHp: 10, playerId: id === 'm' ? 0 : 1, x, y } }
}
function motion(events: CombatEvent[]) { return buildCombatMotion(reconstructCombat({ gameId: 'g', roundNumber: 1, events }), 250) }
const splash = () => motion([placed('m', 'Mage', 1), placed('a', 'Squire', 2, 0, 3), placed('b', 'Squire', 3, 1, 3),
  { sequenceNumber: 4, tick: 4, type: 'ATTACK', data: { attackerId: 'm', targetId: 'a', damage: 10 } },
  { sequenceNumber: 5, tick: 4, type: 'UNIT_DIED', data: { unitId: 'a' } },
  { sequenceNumber: 6, tick: 4, type: 'ATTACK', data: { attackerId: 'm', targetId: 'b', damage: 6 } },
  { sequenceNumber: 7, tick: 8, type: 'COMBAT_ENDED', data: { reason: 'TIME_LIMIT' } },
])
it('delays health until the projectile impact, with one primary and smaller recorded splash hits', () => {
  const m = splash()
  expect(m.cues[3]).toMatchObject({ at: 1200, secondary: false, hasSplash: true })
  expect(m.cues[4].at).toBe(1200)
  expect(m.cues[5]).toMatchObject({ at: 1230, secondary: true, primaryTarget: 'a' })
  expect(sampleCombatMotion(m, 1199).state.units.a.currentHp).toBe(10)
  expect(sampleCombatMotion(m, 1200).state.units.a.currentHp).toBe(0)
  expect(sampleCombatMotion(m, 1229).state.units.b.currentHp).toBe(10)
  expect(sampleCombatMotion(m, 1230).state.units.b.currentHp).toBe(4)
  expect(Object.keys(sampleCombatMotion(m, 1230).state.units)).toHaveLength(3)
})
it('preserves server order when a nominally earlier melee cue follows healing', () => {
  const m = motion([placed('m', 'Healer', 1), placed('a', 'Knight', 2),
    { sequenceNumber: 3, tick: 4, type: 'HEALED', data: { healerId: 'm', targetId: 'm', amount: 0 } },
    { sequenceNumber: 4, tick: 4, type: 'ATTACK', data: { attackerId: 'a', targetId: 'm', damage: 3 } },
    { sequenceNumber: 5, tick: 8, type: 'COMBAT_ENDED', data: { reason: 'TIME_LIMIT' } },
  ])
  expect(m.cues[2].at).toBe(1220)
  expect(m.cues[3].at).toBe(1220)
  expect(sampleCombatMotion(m, 1219).state.units.m.currentHp).toBe(10)
  expect(sampleCombatMotion(m, 1220).state.units.m.currentHp).toBe(7)
})
it('interpolates only the displayed position and keeps health snapshots immutable', () => {
  const m = motion([placed('m', 'Knight', 1),
    { sequenceNumber: 2, tick: 1, type: 'UNIT_MOVED', data: { unitId: 'm', playerId: 0, x: 1, y: 0 } },
    { sequenceNumber: 3, tick: 4, type: 'COMBAT_ENDED', data: { reason: 'PLAYER_VICTORY' } },
  ])
  const sample = sampleCombatMotion(m, 350)
  expect(sample.state.units.m.x).toBe(1)
  expect(sample.units[0].x).toBeCloseTo(.75)
  expect(sample.units[0].bounce).toBeCloseTo(-3)
  expect(sampleCombatMotion(m, 350, true).units[0]).toMatchObject({ x: 1, bounce: 0 })
  expect(m.timeline.steps[0].after.units.m.x).toBe(0)
})
it('retains a lethally hit actor through its same-tick attack and allows the final actor to recover and fade before results', () => {
  const m = motion([placed('m', 'Knight', 1), placed('a', 'Mage', 2),
    { sequenceNumber: 3, tick: 4, type: 'ATTACK', data: { attackerId: 'm', targetId: 'a', damage: 10 } },
    { sequenceNumber: 4, tick: 4, type: 'UNIT_DIED', data: { unitId: 'a' } },
    { sequenceNumber: 5, tick: 4, type: 'ATTACK', data: { attackerId: 'a', targetId: 'm', damage: 4 } },
    { sequenceNumber: 6, tick: 4, type: 'COMBAT_ENDED', data: { reason: 'PLAYER_VICTORY' } },
  ])
  const actor = sampleCombatMotion(m, 1200).units.find(u => u.unit.id === 'a')
  expect(actor?.action?.event.type).toBe('ATTACK')
  expect(actor?.death).toBe(0)
  expect(sampleCombatMotion(m, 1600).units.map(u => u.unit.id)).toEqual(['m'])
  expect(sampleCombatMotion(m, 1200).state.units.m.currentHp).toBe(6)
})
it('late sampling skips expired effects; reduced motion keeps numbers and state', () => {
  const m = splash()
  expect(sampleCombatMotion(m, 1900).effects).toEqual([])
  expect(sampleCombatMotion(m, 1200, true).state.units.a.currentHp).toBe(0)
  expect(sampleCombatMotion(m, 1200, true).effects.some(c => c.event.type === 'ATTACK')).toBe(true)
  expect(sampleCombatMotion(m, 1200, true).units.some(u => u.unit.id === 'a')).toBe(false)
})
it('reaches exactly the engine final state for normal and reduced motion without changing events', () => {
  const m = motion(fixture.events as CombatEvent[])
  for (const reduced of [false, true]) {
    expect(sampleCombatMotion(m, m.settleAt + 1000, reduced).state).toEqual(m.timeline.final)
    expect(sampleCombatMotion(m, m.settleAt + 1000, reduced).effects).toEqual([])
  }
  expect(displayPoint(3, 0, 0)).toEqual({ col: 0, row: 0 })
  expect(displayPoint(3, 0, 1)).toEqual({ col: 7, row: 3 })
})

it('applies a self-heal only at its recorded cue and retains its exact amount', () => {
  const m = motion([placed('m', 'Healer', 1), placed('a', 'Knight', 2),
    { sequenceNumber: 3, tick: 1, type: 'ATTACK', data: { attackerId: 'a', targetId: 'm', damage: 7 } },
    { sequenceNumber: 4, tick: 4, type: 'HEALED', data: { healerId: 'm', targetId: 'm', amount: 5 } },
    { sequenceNumber: 5, tick: 8, type: 'COMBAT_ENDED', data: { reason: 'TIME_LIMIT' } },
  ])
  expect(sampleCombatMotion(m, 1219).state.units.m.currentHp).toBe(3)
  expect(sampleCombatMotion(m, 1220).state.units.m.currentHp).toBe(8)
  expect(sampleCombatMotion(m, 1220).effects.find(c => c.event.type === 'HEALED')?.event.data).toMatchObject({ healerId: 'm', targetId: 'm', amount: 5 })
})

it('starts later-sequence movement on the tick boundary without revealing pending damage', () => {
  const m = motion([placed('m', 'Mage', 1), placed('a', 'Knight', 2),
    { sequenceNumber: 3, tick: 4, type: 'ATTACK', data: { attackerId: 'm', targetId: 'a', damage: 6 } },
    { sequenceNumber: 4, tick: 4, type: 'UNIT_MOVED', data: { unitId: 'a', playerId: 1, x: 1, y: 0 } },
    { sequenceNumber: 5, tick: 8, type: 'COMBAT_ENDED', data: { reason: 'TIME_LIMIT' } },
  ])
  expect(m.cues[3].at).toBe(1000)
  expect(sampleCombatMotion(m, 1100).state.units.a).toMatchObject({ x: 1, currentHp: 10 })
  expect(sampleCombatMotion(m, 1100).units.find(u => u.unit.id === 'a')?.x).toBeCloseTo(.75)
  expect(sampleCombatMotion(m, 1200).state.units.a).toMatchObject({ x: 1, currentHp: 4 })
  expect(sampleCombatMotion(m, 2400).state).toEqual(m.timeline.final)
})

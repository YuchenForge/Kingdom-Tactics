import type { CombatTimeline, CombatUnitState } from './combatState'

export type CombatCue = CombatTimeline['steps'][number] & {
  at: number
  secondary: boolean
  hasSplash: boolean
  primaryTarget?: string
}
export type CombatMotion = { timeline: CombatTimeline; cues: CombatCue[]; settleAt: number }
export const clamp01 = (n: number) => Math.max(0, Math.min(1, n))

/** Match the preview: moves start on the boundary; impact cues preserve recorded HP/death order. */
export function buildCombatMotion(timeline: CombatTimeline, tickMs: number): CombatMotion {
  const primaries = new Map<string, string>()
  let previous = 0
  const cues = timeline.steps.map(step => {
    const { event, before } = step
    let offset = 0
    let secondary = false
    let primaryTarget: string | undefined
    if (event.type === 'ATTACK') {
      const type = before.units[event.data.attackerId].type
      if (type === 'Mage') {
        const key = `${event.tick}:${event.data.attackerId}`
        primaryTarget = primaries.get(key)
        secondary = primaryTarget !== undefined
        if (!secondary) primaries.set(key, event.data.targetId)
        offset = secondary ? 230 : 200
      } else offset = type === 'Ranger' || type === 'Healer' ? 190 : 160
    } else if (event.type === 'HEALED') offset = 220
    const immediate = event.type === 'UNIT_PLACED' || event.type === 'UNIT_MOVED'
    const at = immediate ? event.tick * tickMs : Math.max(previous, event.tick * tickMs + offset)
    if (!immediate) previous = at
    return { ...step, at, secondary, primaryTarget, hasSplash: false }
  })
  for (const cue of cues) {
    cue.hasSplash = !cue.secondary && cue.event.type === 'ATTACK' && cues.some(other => other.secondary
      && other.event.type === 'ATTACK' && cue.event.type === 'ATTACK'
      && other.event.tick === cue.event.tick && other.event.data.attackerId === cue.event.data.attackerId)
  }
  return { timeline, cues, settleAt: timeline.final.tick * tickMs + 1000 }
}

export function cueSource(cue: CombatCue): CombatUnitState | undefined {
  const e = cue.event
  return e.type === 'ATTACK' ? cue.before.units[e.data.attackerId]
    : e.type === 'HEALED' ? cue.before.units[e.data.healerId] : undefined
}
export function cueTarget(cue: CombatCue): CombatUnitState | undefined {
  const e = cue.event
  return e.type === 'ATTACK' || e.type === 'HEALED' ? cue.before.units[e.data.targetId] : undefined
}

export type MotionUnit = {
  unit: CombatUnitState; x: number; y: number; bounce: number; death: number
  action?: CombatCue; hit?: CombatCue
}

/** Sample all visuals directly: no animation completion callbacks or queued HP mutations. */
export function sampleCombatMotion(motion: CombatMotion, elapsed: number, reduced = false) {
  const time = Math.max(0, elapsed)
  let state = motion.timeline.initial
  for (const cue of motion.cues) {
    if (cue.at > time) continue
    // A move may visually precede earlier damage in the same tick. Copy only its own
    // recorded fields; copying the whole after-state would reveal pending HP changes.
    const e = cue.event
    let unit: CombatUnitState | undefined
    if (e.type === 'UNIT_PLACED') unit = cue.after.units[e.data.unitId]
    else if (e.type === 'UNIT_MOVED') unit = { ...state.units[e.data.unitId], x: e.data.x, y: e.data.y }
    else if (e.type === 'ATTACK' || e.type === 'HEALED') {
      unit = { ...state.units[e.data.targetId], currentHp: cue.after.units[e.data.targetId].currentHp }
    } else if (e.type === 'UNIT_DIED') unit = { ...state.units[e.data.unitId], dead: true, currentHp: 0 }
    state = { ...state, sequenceNumber: e.sequenceNumber, tick: e.tick,
      outcome: e.type === 'COMBAT_ENDED' ? e.data.reason : state.outcome,
      units: unit ? { ...state.units, [unit.id]: unit } : state.units }
  }
  const units: MotionUnit[] = []
  for (const unit of Object.values(state.units)) {
    const deathCue = motion.cues.find(c => c.event.type === 'UNIT_DIED' && c.event.data.unitId === unit.id)
    const actions = motion.cues.filter(c => cueSource(c)?.id === unit.id && !c.secondary)
    const lastAction = actions.reduce((last, c) => c.event.tick === deathCue?.event.tick ? Math.max(last, c.at + 80) : last, 0)
    const deathStart = deathCue ? Math.max(deathCue.at + 60, lastAction) : Infinity
    const deathEnd = Math.min(deathStart + 250, motion.settleAt)
    const death = deathCue && time >= deathCue.at
      ? reduced ? 1 : clamp01((time - Math.min(deathStart, deathEnd - 40)) / Math.max(40, deathEnd - deathStart)) : 0
    if (death >= 1) continue
    let x = unit.x, y = unit.y, bounce = 0
    if (!reduced) {
      const move = motion.cues.findLast(c => c.at <= time && c.event.type === 'UNIT_MOVED' && c.event.data.unitId === unit.id)
      if (move && time < move.at + 200) {
        const p = clamp01((time - move.at) / 200), ease = 1 - (1 - p) ** 2
        const old = move.before.units[unit.id]
        x = old.x + (unit.x - old.x) * ease
        y = old.y + (unit.y - old.y) * ease
        bounce = -3 * Math.sin(p * Math.PI)
      }
    }
    const action = actions.findLast(c => time >= c.at - 160 && time < c.at + 80)
    const hit = motion.cues.findLast(c => cueTarget(c)?.id === unit.id && time >= c.at && time < c.at + (c.event.type === 'HEALED' ? 240 : 100))
    units.push({ unit, x, y, bounce, death, action, hit })
  }
  const effects = motion.cues.filter(c => {
    const e = c.event
    if (e.type !== 'ATTACK' && e.type !== 'HEALED' && e.type !== 'UNIT_DIED') return false
    const start = e.type === 'UNIT_DIED' || reduced || c.secondary ? c.at : c.at - (e.type === 'HEALED' ? 150 : 100)
    return time >= start && time < Math.min(c.at + 550, motion.settleAt + 300)
  })
  return { state, units, effects, time }
}

/** Global engine coordinates to the existing horizontal board, for either viewer. */
export function displayPoint(x: number, y: number, seat: number) {
  return seat === 1 ? { col: 7 - y, row: x } : { col: y, row: 3 - x }
}

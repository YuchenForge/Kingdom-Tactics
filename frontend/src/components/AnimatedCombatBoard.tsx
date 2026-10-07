import '../styles/combat-preview.css'
import { useLayoutEffect, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import type { GameState, RoundResult } from '../types'
import type { CombatMotion, CombatCue } from '../combat/combatMotion'
import { clamp01, cueSource, cueTarget, displayPoint, sampleCombatMotion } from '../combat/combatMotion'
import { useReducedMotion } from '../hooks/useReducedMotion'

export type PlanningBounds = { left: number; top: number; width: number; height: number }
type Size = PlanningBounds
const ease = (p: number) => 1 - (1 - clamp01(p)) ** 3

/** Pixel geometry comes from the responsive board rectangle, not the viewport. */
function geometry(size: Size, x: number, y: number, seat: number) {
  const p = displayPoint(x, y, seat), w = size.width / 8, h = size.height / 4
  return { x: (p.col + .5) * w, y: (p.row + .5) * h, w, h }
}
function mergePoint(p: ReturnType<typeof geometry>, size: Size, progress: number, elapsed: number, origin?: PlanningBounds) {
  // First shrink the observed planning board, then rotate it into the left half.
  // Counter-rotation is implicit: unit portraits remain upright while tile centers rotate.
  const zoom = ease((elapsed + 2200) / 1200)
  const q = ease(progress)
  const side = p.x < size.width / 2 ? -1 : 1
  const half = size.width / 2
  const originalScale = origin ? origin.width / half : 1.25
  const scale = side === -1 ? originalScale + (1 - originalScale) * zoom : 1
  const angle = side * (1 - q) * Math.PI / 2
  const targetCx = size.width * (side === -1 ? .25 : .75)
  const startCx = origin ? origin.left - size.left + origin.width / 2 : size.width / 2
  const startCy = origin ? origin.top - size.top + origin.height / 2 : size.height / 2
  const cx = side === -1 ? startCx + (targetCx - startCx) * zoom : targetCx
  const cy = side === -1 ? startCy + (size.height / 2 - startCy) * zoom : size.height / 2
  const dx = p.x - targetCx, dy = p.y - size.height / 2
  return { ...p, x: cx + (dx * Math.cos(angle) - dy * Math.sin(angle)) * scale,
    y: cy + (dx * Math.sin(angle) + dy * Math.cos(angle)) * scale, angle, scale,
    opacity: side === -1 ? 1 : clamp01(progress * 4) }
}

function Effects({ cues, time, size, seat, reduced }: { cues: CombatCue[]; time: number; size: Size; seat: number; reduced: boolean }) {
  return <svg className="combat-effects" viewBox={`0 0 ${size.width} ${size.height}`} aria-hidden="true">
    {cues.map((cue, index) => {
      const event = cue.event, source = cueSource(cue), target = cueTarget(cue)
      if (event.type === 'UNIT_DIED') {
        const age = (time - cue.at - 60) / 220
        if (reduced || age < 0 || age >= 1) return null
        const u = cue.before.units[event.data.unitId], p = geometry(size, u.x, u.y, seat)
        return <ellipse key={event.sequenceNumber} cx={p.x} cy={p.y - p.h * .15} rx={size.width * .025 * (.7 + .65 * age)} ry={size.height * .015 * (.7 + .65 * age)} fill="#adbcc5" opacity={.31 * (1 - age)} style={{ filter: 'blur(2px)' }} />
      }
      if (!source || !target) return null
      const a = geometry(size, source.x, source.y, seat), b = geometry(size, target.x, target.y, seat)
      const labelOffset = cues.slice(0, index).filter(other => cueTarget(other)?.id === target.id && time >= other.at).length * 16
      const age = time - cue.at, heal = event.type === 'HEALED', mage = source.type === 'Mage'
      const ranged = mage || source.type === 'Ranger' || source.type === 'Healer'
      const travelMs = heal ? 150 : 100, travel = clamp01((age + travelMs) / travelMs)
      const color = heal ? '#a7f3c3' : mage ? '#c4a1ed' : '#f5df87'
      const duration = heal ? 240 : 120, progress = clamp01(age / duration)
      const radius = size.width * (heal ? .045 : cue.secondary ? .02 : .035)
      const cx = b.x, cy = b.y - b.h * .15
      const angle = Math.atan2(b.y - a.y, b.x - a.x) * 180 / Math.PI
      const px = a.x + (b.x - a.x) * travel, py = a.y - a.h * .1 + (b.y - a.y) * travel - (heal ? 18 * (1 - Math.abs(2 * travel - 1)) : 0)
      return <g key={event.sequenceNumber} data-effect={heal ? 'heal' : cue.secondary ? 'splash' : 'primary'}>
        {!reduced && !cue.secondary && age < 0 && (ranged || heal) && <g transform={`translate(${px} ${py}) rotate(${angle})`}>
          {heal ? <circle r="4" fill="#d5efac" style={{ filter: 'drop-shadow(0 0 8px #89cfab)' }} />
            : source.type === 'Ranger' ? <g fill="#f5df87"><rect x="-12.5" y="-1.5" width="25" height="3" /><path d="M12 -4 L19 0 L12 4Z" /></g>
            : <ellipse rx="8" ry="4" fill="#cfb5ff" style={{ filter: 'drop-shadow(0 0 12px #c9a5ff)' }} />}
        </g>}
        {age >= 0 && <g>
          {age < duration && <circle cx={cx} cy={cy} r={radius * (reduced ? 1 : .7 + .65 * progress)} fill="none" stroke={cue.secondary ? '#cbb8ef' : color} strokeWidth={heal || cue.secondary ? 2 : 3} opacity={1 - progress} style={{ filter: heal ? 'drop-shadow(0 0 12px #89cfab66)' : undefined }} />}
          {cue.hasSplash && !reduced && age < 180 && <circle cx={cx} cy={cy} r={size.width * .0625 * (.7 + 1.5 * age / 180)} fill="none" stroke="#c4a1ed" strokeWidth="2" opacity={1 - age / 180} style={{ filter: 'drop-shadow(0 0 10px #bb8feb50)' }} />}
          <text x={cx} y={cy - labelOffset - (reduced ? 0 : 24 * clamp01(age / 550))} textAnchor="middle" dominantBaseline="middle" fill={heal ? '#a7f3c3' : '#ffb6ba'} fontSize={size.width < 450 ? 14 : 19} fontWeight="800" opacity={1 - clamp01(age / 550)} style={{ filter: 'drop-shadow(0 2px 4px #000)' }}>
            {heal ? `+${event.type === 'HEALED' ? event.data.amount : 0}` : event.type === 'ATTACK' ? `−${event.data.damage}` : ''}
          </text>
        </g>}
      </g>
    })}
  </svg>
}

export default function AnimatedCombatBoard({ state, motion, elapsed, merge, resultReady, planningBounds, result }: {
  state: GameState; motion: CombatMotion; elapsed: number; merge: number; resultReady: boolean; planningBounds?: PlanningBounds; result?: RoundResult
}) {
  const reduced = useReducedMotion()
  const ref = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState<Size>({ width: 800, height: 400, left: 0, top: 0 })
  useLayoutEffect(() => {
    const node = ref.current
    if (!node) return
    const update = () => {
      const rect = node.getBoundingClientRect()
      if (rect.width > 0 && rect.height > 0) setSize({ width: rect.width, height: rect.height, left: rect.left, top: rect.top })
    }
    update()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(update)
    observer.observe(node)
    return () => observer.disconnect()
  }, [])
  const sample = sampleCombatMotion(motion, elapsed, reduced)
  const progress = reduced ? 1 : clamp01(merge)
  const place = (x: number, y: number) => mergePoint(geometry(size, x, y, state.yourSeat), size, progress, reduced ? 0 : elapsed, planningBounds)
  const outcome = motion.timeline.final.outcome
  const victory = outcome === 'PLAYER_VICTORY' ? state.yourSeat === 0 : outcome === 'ENEMY_VICTORY' ? state.yourSeat === 1 : null
  const title = merge < 1 ? 'Formations locked' : !resultReady ? 'Combat' : victory === null ? (outcome === 'DRAW' ? 'Draw' : 'Time limit') : victory ? 'Victory' : 'Defeat'
  const keepFeedback = (seat: number) => resultReady && result?.roundNumber === state.currentRound
    ? <small className="keep-damage" aria-label={`${seat === state.yourSeat ? 'Your' : 'Opponent'} Keep damage`}>
      {result.keepDamage[String(seat)] > 0 ? `−${result.keepDamage[String(seat)]} HP` : 'No damage'}
    </small> : null
  const total = state.combatPresentation
    ? Date.parse(state.combatPresentation.endsAt) - Date.parse(state.combatPresentation.startsAt)
    : motion.settleAt + 3000
  return <section className="combat-frame motion-frame" aria-label="Merged combat board">
    <div className="motion-framehead"><h2>{title}</h2><span>Round {state.currentRound}</span></div>
    <div className="combat-field">
      <aside className="keep friendly-keep"><img src="/assets/keep/keep.svg" alt="" /><strong>Your Keep</strong><span>{state.yourKeepHp} HP</span>{keepFeedback(state.yourSeat)}</aside>
      <div ref={ref} style={{ width: '100%', maxWidth: planningBounds ? planningBounds.width * 1.75 : undefined, justifySelf: 'center' }} className={`animated-grid ${progress < 1 ? 'merging' : ''}`} role="grid" aria-label="Eight columns by four rows">
        {Array.from({ length: 4 }, (_, row) => <div className="animated-row" role="row" key={row}>
          {Array.from({ length: 8 }, (_, col) => {
            const x = state.yourSeat === 1 ? row : 3 - row, y = state.yourSeat === 1 ? 7 - col : col
            const original = geometry(size, x, y, state.yourSeat), p = place(x, y)
            const unit = sample.units.find(u => !u.unit.dead && u.unit.x === x && u.unit.y === y)?.unit
            return <div key={col} className={`combat-cell ${col < 4 ? 'friendly-half' : 'enemy-half'}`} role="gridcell"
              style={{ transform: `translate(${p.x - original.x}px, ${p.y - original.y}px) rotate(${p.angle}rad) scale(${p.scale})`, opacity: p.opacity }}
              aria-label={`Global ${x},${y}: ${unit ? `${unit.type}, ${unit.currentHp} HP, ${unit.seat === state.yourSeat ? 'friendly' : 'enemy'}` : 'empty'}`} />
          })}
        </div>)}
        {sample.units.map(pose => {
          const { unit, action, hit } = pose, p = place(pose.x, pose.y)
          const target = action && cueTarget(action), source = hit && cueSource(hit)
          const targetPoint = target && geometry(size, target.x, target.y, state.yourSeat)
          const sourcePoint = source && geometry(size, source.x, source.y, state.yourSeat)
          const direction = (q: { x: number; y: number } | undefined) => {
            const dx = q ? q.x - p.x : 0, dy = q ? q.y - p.y : 0, length = Math.hypot(dx, dy) || 1
            return { x: dx / length, y: dy / length }
          }
          const d = direction(targetPoint), h = direction(sourcePoint)
          const age = action ? sample.time - action.at : 1000
          const ranged = ['Ranger', 'Mage', 'Healer'].includes(unit.type)
          const strength = ranged ? 2 : unit.type === 'Knight' ? 15 : unit.type === 'Squire' ? 8 : 5
          const thrust = age < -100 ? -3 * clamp01((age + 160) / 60) : age < 0 ? -3 + (strength + 3) * clamp01((age + 100) / 100) : strength * (1 - clamp01(age / 80))
          const hop = action && unit.type === 'Squire' ? -3 * (age < 0 ? clamp01((age + 100) / 100) : 1 - clamp01(age / 80)) : 0
          const lean = action && age < 0 ? -d.x * 3 * (age < -100 ? clamp01((age + 160) / 60) : 1 - clamp01((age + 100) / 100)) : 0
          const hitAge = hit ? sample.time - hit.at : 1000
          const flash = hitAge < 35 ? clamp01(hitAge / 35) : 1 - clamp01((hitAge - 35) / 65)
          const reaction = hit?.event.type === 'ATTACK' ? (unit.type === 'Shieldbearer' ? .8 : 3) * flash : 0
          const charge = age < -100 ? clamp01((age + 160) / 60) : 1 - clamp01((age + 100) / 100)
          const glow = action && unit.type === 'Mage' && age < 0 ? `brightness(${1 + .25 * charge}) drop-shadow(0 0 ${5 * charge}px #b797e5)` : 'none'
          const healingGlow = hit?.event.type === 'HEALED' ? Math.sin(clamp01(hitAge / 240) * Math.PI) : 0
          const style: CSSProperties = { left: `${p.x / size.width * 100}%`, top: `${p.y / size.height * 100}%`, width: `${p.w / size.width * 100}%`, height: `${p.h / size.height * 100}%`, opacity: (1 - pose.death) * p.opacity, transform: `translate(-50%,-50%) scale(${p.scale})` }
          return <div key={unit.id} className={`combat-sprite ${unit.seat === state.yourSeat ? 'friendly' : 'enemy'}`} style={style} data-unit={unit.id}>
            <div className="combat-backplate">
            <div className="combat-death" style={{ transform: reduced ? undefined : `rotate(${pose.death * (unit.seat === state.yourSeat ? -9 : 9)}deg) scale(${1 - pose.death * .22})` }}>
              <div className="combat-action" style={{ transform: reduced ? undefined : `translate(${d.x * thrust}px, ${d.y * thrust + pose.bounce + hop}px) rotate(${lean}deg)`, filter: reduced ? undefined : glow }}>
                <div className="combat-reaction" style={{ transform: reduced ? undefined : `translate(${-h.x * reaction}px, ${-h.y * reaction}px)`, filter: hit ? `brightness(${1 + (reduced ? .08 : .22) * (healingGlow || flash)}) drop-shadow(0 0 ${reduced ? 0 : healingGlow * 7}px #89cfab)` : undefined }}>
                  <img src={`/assets/units/${unit.type.toLowerCase()}.svg`} alt={unit.type} />
                </div>
              </div>
            </div>
            <span className="unit-level">{'★'.repeat(unit.level)}</span>
            <span className="combat-health"><span className="combat-hp-value">{unit.currentHp}</span><span className="combat-hp-track" role="meter" aria-label={`${unit.type} health`} aria-valuemin={0} aria-valuemax={unit.maxHp} aria-valuenow={unit.currentHp}><i style={{ width: `${Math.max(0, unit.currentHp / unit.maxHp * 100)}%` }} /></span></span>
            </div>
          </div>
        })}
        <Effects cues={sample.effects} time={sample.time} size={size} seat={state.yourSeat} reduced={reduced} />
      </div>
      <aside className="keep opponent-keep"><img src="/assets/keep/keep.svg" alt="" /><strong>Opponent</strong><span>{state.opponentKeepHp} HP</span>{keepFeedback(1 - state.yourSeat)}</aside>
    </div>
    <div className="motion-framefoot"><span role="status">{merge < 1 ? elapsed < -1000 ? 'Your formation takes its place on the battlefield.' : 'Both formations rotate and join the battlefield.' : resultReady ? 'Round resolved' : 'The battle is underway.'}</span><span>{merge < 1 ? 'Preparing combat…' : `Tick ${Math.min(motion.timeline.final.tick, Math.max(0, Math.floor(elapsed / (state.combatPresentation?.tickDurationMs ?? 250))))} / ${motion.timeline.final.tick}`}</span></div>
    <div className="motion-progress" aria-hidden="true"><i style={{ width: `${clamp01((elapsed + 1000) / (total + 1000)) * 100}%` }} /></div>
  </section>
}

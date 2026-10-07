import { useEffect, useMemo, useState } from 'react'
import type { GameState, RoundResult } from '../types'
import type { CombatRecording } from '../types/combat'
import { combatStateAtTick, reconstructCombat, matchesRoundResult } from '../combat/combatState'
import { buildCombatMotion } from '../combat/combatMotion'
import { playbackPosition, serverNow } from '../combat/playbackClock'

/** Read the shared timeline directly. Missing time never starts a local replay from zero. */
export function useCombatPlayback(state: GameState | undefined, recording: CombatRecording | undefined, result?: RoundResult) {
  const [localNow, setLocalNow] = useState(() => performance.now())
  const gameId = state?.gameId
  const roundNumber = state?.currentRound
  const phase = state?.state
  const timing = state?.state === 'ROUND_RESULT' && state.combatPresentation?.roundNumber === state.currentRound
    ? state.combatPresentation : null
  const sample = state?.clockSample
  const reconstructed = useMemo(() => {
    if (!recording || recording.gameId !== gameId || recording.roundNumber !== roundNumber
        || phase !== 'ROUND_RESULT') return null
    try {
      const timeline = reconstructCombat(recording)
      if (!timeline.final.outcome) throw new Error('Combat recording has no final outcome.')
      return { timeline, error: null }
    } catch (error) {
      return { timeline: null, error: error instanceof Error ? error : new Error('Could not reconstruct combat.') }
    }
  }, [recording, gameId, roundNumber, phase])

  const timeline = reconstructed?.timeline
  const motion = useMemo(() => timeline && timing ? buildCombatMotion(timeline, timing.tickDurationMs) : null,
    [timeline, timing])
  let position: ReturnType<typeof playbackPosition> | null = null
  let error = reconstructed?.error ?? null
  if (!error && timeline && result && result.roundNumber === roundNumber && !matchesRoundResult(timeline.final, result)) {
    error = new Error('Recorded combat does not match the server result.')
  }
  try {
    if (timing && sample) position = playbackPosition(timing, serverNow(sample, Math.max(localNow, sample.receivedAt)))
  } catch (failure) {
    error = failure instanceof Error ? failure : new Error('Could not read combat timing.')
  }
  const active = !!timing && !!sample && !error && position?.phase !== 'ended'
  useEffect(() => {
    if (!active) return
    const update = () => setLocalNow(performance.now())
    update()
    // Recompute elapsed time, never count callbacks. Throttled tabs skip missed ticks.
    const timer = window.setInterval(update, 32)
    window.addEventListener('focus', update)
    document.addEventListener('visibilitychange', update)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', update)
      document.removeEventListener('visibilitychange', update)
    }
  }, [active, state?.gameId, state?.currentRound])

  const frame = !error && timeline && position
    ? position.phase === 'result' || position.phase === 'ended' ? timeline.final
      : combatStateAtTick(timeline, position.tick)
    : null
  const elapsed = timing && sample ? serverNow(sample, Math.max(localNow, sample.receivedAt)) - Date.parse(timing.startsAt) : 0
  const resultReady = !position || position.phase === 'ended'
    || (position.phase === 'result' && elapsed >= (motion?.settleAt
      ?? Date.parse(timing!.combatEndsAt) - Date.parse(timing!.startsAt) + 1000))
  return { frame, position, error, motion: error ? null : motion, elapsed,
    merge: Math.max(0, Math.min(1, (elapsed + 1000) / 1000)), resultReady,
    // Hide this round's result until its shared result interval, including while events load.
    presenting: !error && !!position && !resultReady }
}

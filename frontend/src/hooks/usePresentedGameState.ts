import { useEffect, useState } from 'react'
import type { GameState } from '../types'
import { serverNow } from '../combat/playbackClock'

function scheduled(state: GameState | undefined) {
  const timing = state?.combatPresentation
  return state?.state === 'ROUND_RESULT' && timing?.roundNumber === state.currentRound
    && state.clockSample && Number.isFinite(Date.parse(timing.endsAt))
}

/** Keep an observed round on one monotonic clock through its persisted end.
 * Polling remains live, but cannot seek playback forward or unmount its final cues.
 * Reloads still join current server time; no recording is restarted locally.
 */
export function usePresentedGameState(live: GameState | undefined, gameId: string | undefined) {
  const [snapshot, setSnapshot] = useState({ input: live, shown: live })
  const { shown } = snapshot
  const [now, setNow] = useState(() => performance.now())
  const currentTime = Math.max(now, live?.clockSample?.receivedAt ?? 0, shown?.clockSample?.receivedAt ?? 0)
  const sameGame = shown?.gameId === gameId && live?.gameId === gameId
  const sameRound = sameGame && shown?.currentRound === live?.currentRound
  const holding = !!(sameGame && scheduled(shown)
    && serverNow(shown!.clockSample!, currentTime) < Date.parse(shown!.combatPresentation!.endsAt))
  let next = shown
  if (live !== snapshot.input || (!holding && shown !== live)) {
    // Use fresh fields, but preserve this round's original schedule and anchor.
    next = holding
      ? sameRound && live?.state === 'ROUND_RESULT'
        ? { ...live, combatPresentation: shown!.combatPresentation, clockSample: shown!.clockSample }
        : shown
      : live
    setSnapshot({ input: live, shown: next })
  }
  useEffect(() => {
    if (!holding) return
    const update = () => setNow(performance.now())
    const timer = window.setInterval(update, 32)
    window.addEventListener('focus', update)
    document.addEventListener('visibilitychange', update)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', update)
      document.removeEventListener('visibilitychange', update)
    }
  }, [holding, gameId, shown?.currentRound])
  return next
}

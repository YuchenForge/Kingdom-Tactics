import { ApiError } from './client'
import { getCombatEventsPage } from './gameApi'
import type { CombatEvent, CombatRecording } from '../types/combat'

function wait(signal: AbortSignal, ms: number): Promise<void> {
  signal.throwIfAborted()
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(signal.reason) }
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, ms)
    signal.addEventListener('abort', abort, { once: true })
  })
}

function transient(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 0 || error.status === 429 || error.status >= 500)
}

/** Only return a complete recording. Retry a failed page without skipping its cursor. */
export async function loadCombatRecording(gameId: string, roundNumber: number, signal: AbortSignal): Promise<CombatRecording> {
  const events = new Map<number, CombatEvent>()
  let cursor = 0
  let failures = 0
  while (true) {
    signal.throwIfAborted()
    let page
    try {
      page = await getCombatEventsPage(gameId, roundNumber, cursor, signal)
      signal.throwIfAborted()
      failures = 0
    } catch (error) {
      signal.throwIfAborted()
      if (!transient(error) || failures >= 3) throw error
      await wait(signal, 1000 * 2 ** failures++)
      continue
    }
    if (page.roundNumber !== roundNumber) throw new Error('Combat recording belongs to a different round.')
    if (!page.complete) {
      // TX2 has not committed. An empty response is not a finished recording.
      await wait(signal, 1000)
      continue
    }
    for (const event of page.events) {
      if (!Number.isSafeInteger(event.sequenceNumber) || event.sequenceNumber < 1) {
        throw new Error('Invalid combat event sequence.')
      }
      events.set(event.sequenceNumber, event)
    }
    const ordered = [...events.values()].sort((a, b) => a.sequenceNumber - b.sequenceNumber)
    if (ordered.some((event, index) => event.sequenceNumber !== index + 1)) {
      throw new Error('Combat recording contains a sequence gap.')
    }
    const last = ordered.at(-1)?.sequenceNumber ?? 0
    if (page.nextAfterSequence !== last || (page.hasMore && last <= cursor)) {
      throw new Error('Combat pagination did not advance correctly.')
    }
    if (!page.hasMore) return { gameId, roundNumber, events: ordered }
    cursor = last
  }
}

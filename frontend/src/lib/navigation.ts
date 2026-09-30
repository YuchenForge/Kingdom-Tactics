import type { GameOverview } from '../types'

// Only known in-app destinations may be used after authentication.
export function safeNext(value: string | null): string {
  if (!value || /[\\\r\n]/.test(value)) return '/'
  return /^\/(?:join\/[^/?#%]+|games\/[^/?#%]+(?:\/(?:play|result))?)(?:\?[^#]*)?$/.test(value)
    ? value : '/'
}

export function authPath(path: string): string {
  return `/auth?next=${encodeURIComponent(safeNext(path))}`
}

export function gamePath(game: GameOverview): string {
  const base = `/games/${encodeURIComponent(game.gameId)}`
  return game.state === 'WAITING_FOR_PLAYERS' ? base
    : `${base}/${game.state === 'FINISHED' ? 'result' : 'play'}`
}

export function inviteDestination(input: string): string | null {
  const value = input.trim()
  if (/^[a-zA-Z0-9-]+$/.test(value)) return `/join/${value}`
  try {
    const url = new URL(value, window.location.origin)
    if (url.origin !== window.location.origin || !/^\/join\/[a-zA-Z0-9-]+$/.test(url.pathname)) return null
    return url.pathname
  } catch { return null }
}

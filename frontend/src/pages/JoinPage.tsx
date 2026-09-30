import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/client'
import { getGame, joinGame } from '../api/gameApi'
import { useAuth } from '../hooks/useAuth'
import { gamePath } from '../lib/navigation'
import type { GameOverview } from '../types'

export default function JoinPage() {
  const { gameId = '' } = useParams()
  const auth = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState('')
  // Strict Mode reattaches the effect to the same promise rather than posting twice.
  const request = useRef<{ id: string; promise: Promise<GameOverview> } | null>(null)
  useEffect(() => {
    let active = true
    setError('')
    if (request.current?.id !== gameId) {
      request.current = { id: gameId, promise: joinGame(gameId).catch((failure: unknown) => {
        if (failure instanceof ApiError && failure.status === 400 && failure.code === 'ALREADY_IN_GAME') return getGame(gameId)
        throw failure
      }) }
    }
    void request.current.promise.then((game) => {
      if (active) navigate(gamePath(game), { replace: true })
    }).catch((failure: unknown) => {
      if (!active) return
      if (failure instanceof ApiError && failure.status === 401) auth.expire()
      else setError(failure instanceof ApiError ? failure.message : 'Could not join this game.')
    })
    return () => { active = false }
    // The request belongs to this route's game id; auth is validated by RequireAuth.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gameId, navigate])
  return <main><h1>Join game</h1>{error ? <p role="alert">{error}</p> : <p role="status">Joining…</p>}<Link to="/">Back home</Link></main>
}

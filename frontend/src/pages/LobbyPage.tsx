import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router'
import { ApiError } from '../api/client'
import { getGame } from '../api/gameApi'
import { useAuth } from '../hooks/useAuth'
import { gamePath } from '../lib/navigation'

export default function LobbyPage() {
  const { gameId = '' } = useParams()
  const auth = useAuth()
  const navigate = useNavigate()
  const input = useRef<HTMLInputElement>(null)
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState('')
  const invite = `${window.location.origin}/join/${encodeURIComponent(gameId)}`
  const query = useQuery({
    queryKey: ['game', gameId], queryFn: () => getGame(gameId),
    retry: false,
    refetchInterval: (query) => {
      if (query.state.error instanceof ApiError && [401, 403, 404].includes(query.state.error.status)) return false
      return !query.state.data || query.state.data.state === 'WAITING_FOR_PLAYERS' ? 1000 : false
    },
  })
  useEffect(() => {
    if (query.error instanceof ApiError && query.error.status === 401) auth.expire()
    else if (query.error instanceof ApiError && query.error.status === 403) navigate(`/join/${encodeURIComponent(gameId)}`, { replace: true })
    else if (!query.error && query.data && query.data.state !== 'WAITING_FOR_PLAYERS') navigate(gamePath(query.data), { replace: true })
  }, [query.data, query.error, gameId, navigate, auth])
  useEffect(() => {
    if (!copied) return
    const timer = window.setTimeout(() => setCopied(false), 2000)
    return () => window.clearTimeout(timer)
  }, [copied])
  return <main><Link to="/">Home</Link><h1>Game lobby</h1><p>Game id: {gameId}</p>
    {query.isPending && <p role="status">Loading game…</p>}
    {query.error && <p role="alert">{query.error instanceof ApiError ? query.error.message : 'Reconnecting…'}</p>}
    {query.data && <><ul>{query.data.players.map((player) => <li key={player.playerId}>{player.username}</li>)}</ul>
      {query.data.state === 'WAITING_FOR_PLAYERS' && <p role="status">Waiting for opponent…</p>}</>}
    <label>Invite link<input ref={input} readOnly value={invite} /></label>
    <button onClick={async () => {
      setCopyError('')
      try { await navigator.clipboard.writeText(invite); setCopied(true) }
      catch { input.current?.focus(); input.current?.select(); setCopyError('Copy the selected invite link manually.') }
    }}>{copied ? 'Copied' : 'Copy invite link'}</button>
    {copyError && <p role="status">{copyError}</p>}
    <button onClick={auth.logout}>Log out</button>
  </main>
}

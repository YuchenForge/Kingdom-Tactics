import { useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { createGame } from '../api/gameApi'
import { ApiError } from '../api/client'
import { useAuth } from '../hooks/useAuth'
import { inviteDestination } from '../lib/navigation'

export default function GameActions() {
  const auth = useAuth()
  const navigate = useNavigate()
  const busyRef = useRef(false)
  const [busy, setBusy] = useState(false)
  const [invite, setInvite] = useState('')
  const [error, setError] = useState('')
  async function create() {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    setError('')
    try {
      const game = await createGame()
      navigate(`/games/${encodeURIComponent(game.gameId)}`)
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 401) auth.expire()
      else setError(failure instanceof ApiError ? failure.message : 'Could not create a game.')
    } finally { busyRef.current = false; setBusy(false) }
  }
  return <section aria-label="Play with a friend">
    <button disabled={busy} onClick={() => void create()}>{busy ? 'Creating…' : 'New game'}</button>
    <form onSubmit={(event) => {
      event.preventDefault()
      const destination = inviteDestination(invite)
      if (destination) navigate(destination)
      else setError('Enter a game id or an invite link from this site.')
    }}>
      <label>Game id or invite link<input value={invite} onChange={(event) => setInvite(event.target.value)} required /></label>
      <button type="submit">Join game</button>
    </form>
    {error && <p role="alert">{error}</p>}
  </section>
}

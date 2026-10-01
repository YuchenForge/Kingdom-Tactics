import { Link, useParams } from 'react-router'
import { ApiError } from '../api/client'
import { useAuth } from '../hooks/useAuth'
import { useGameState } from '../hooks/useGameState'

export default function GameBoardPage() {
  const { gameId } = useParams()
  const auth = useAuth()
  const game = useGameState(gameId)
  const state = game.state
  const result = game.roundResult
  if (!gameId) return <main><h1>Game not found</h1><Link to="/">Home</Link></main>
  return <main><Link to="/">Home</Link><h1>Game board</h1><p>Game id: {gameId}</p>
    {game.isLoading && <p role="status">Loading game…</p>}
    {game.isReconnecting && <p role="alert">Reconnecting… Actions are disabled until the connection recovers.</p>}
    {game.error && !game.isReconnecting && <p role="alert">{game.error instanceof ApiError ? game.error.message : 'Could not load the game.'}</p>}
    {state && <section aria-label="Game status">
      <p>Round {state.currentRound}</p><p>Phase: {state.state}</p>
      <p>Gold: {state.yourGold}</p><p>Your Keep HP: {state.yourKeepHp}</p><p>Opponent Keep HP: {state.opponentKeepHp}</p>
      <p>{state.isLocked ? 'Board locked' : 'Board unlocked'}</p>
      <p>{state.opponentIsLocked ? 'Opponent locked' : 'Opponent not locked'}</p>
      {game.showDeadline && <p>Planning deadline: <time dateTime={state.planningDeadline!}>{state.planningDeadline}</time></p>}
      <p>{game.canMutate ? 'Planning actions available.' : 'Planning actions unavailable.'}</p>
    </section>}
    {game.isResolving && <p role="status">Resolving…</p>}
    {result && <section aria-label="Last round result"><h2>Round {result.roundNumber} result</h2>
      <p>Outcome: {result.outcome} (PLAYER = seat 0; ENEMY = seat 1)</p>
      {Object.entries(result.keepDamage).map(([seat, damage]) => <p key={seat}>Seat {seat}: {damage} Keep damage; {result.keepHpAfter[seat]} HP remaining.</p>)}
    </section>}
    {game.roundResultError && <p role="alert">Could not load the last round result. Live game updates continue.</p>}
    <button onClick={auth.logout}>Log out</button>
  </main>
}

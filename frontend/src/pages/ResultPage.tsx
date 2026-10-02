import '../styles/result-page.css'
import { useEffect } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router'
import GameActions from '../components/GameActions'
import { getGame, getMatchResult, isWrongGameState } from '../api/gameApi'
import { ApiError } from '../api/client'
import { useAuth } from '../hooks/useAuth'

export default function ResultPage() {
  const { gameId } = useParams()
  const auth = useAuth()
  const query = useQuery({
    queryKey: ['matchResult', gameId],
    queryFn: () => getMatchResult(gameId!),
    enabled: !!gameId,
    staleTime: Infinity,
    retry: false,
  })
  const overview = useQuery({ queryKey: ['game', gameId], queryFn: () => getGame(gameId!), enabled: !!gameId, staleTime: Infinity, retry: false })
  const yourSeat = overview.data?.players.find(player => player.playerId === auth.user?.userId)?.seat
  const unauthorized = query.error instanceof ApiError && query.error.status === 401
  useEffect(() => {
    if (unauthorized) auth.expire()
  }, [unauthorized, auth])
  const result = query.data
  const outcome = result?.winnerId == null ? 'Draw'
    : !auth.user ? `${result.winnerUsername ?? 'Player'} wins`
    : result.winnerId === auth.user.userId ? 'Victory' : 'Defeat'
  return <main className="result-page">
    <h1>Match result</h1>
    <details className="match-details"><summary>Match details</summary><p>Game id: {gameId}</p></details>
    {query.isPending && <p role="status">Loading match result…</p>}
    {query.error && !unauthorized && <section role="alert">
      <p>{isWrongGameState(query.error) ? 'This match is still in progress.'
        : query.error instanceof ApiError ? query.error.message : 'Could not load the match result.'}</p>
      {isWrongGameState(query.error) ? <Link to={`/games/${encodeURIComponent(gameId!)}/play`}>Return to game</Link>
        : <button disabled={query.isFetching} onClick={() => void query.refetch()}>Try again</button>}
    </section>}
    {result && <section aria-label="Final match outcome">
      <h2 className={`result-outcome ${outcome.toLowerCase()}`}>{outcome}</h2>
      <p>{result.winnerId == null ? 'The match ended in a draw.'
        : `${result.winnerUsername ?? 'Winner'} defeated ${result.loserUsername ?? 'their opponent'}.`}</p>
      <div className="result-meta"><p>Ended in round {result.finalRound} of 8</p>
      <p>Duration: {Math.floor(result.durationSeconds / 60)}m {result.durationSeconds % 60}s</p></div>
      <h3>Final Keep HP</h3>
      <ul className="result-keeps">{result.finalKeepHp.map((hp, seat) => <li className={`${seat === yourSeat ? 'your-result-keep' : 'opponent-result-keep'} ${hp === 0 ? 'defeated-keep' : ''}`} key={seat}><img src="/assets/keep/keep.svg" alt="" /><span>{yourSeat == null ? overview.data?.players.find(player => player.seat === seat)?.username ?? 'Player' : seat === yourSeat ? 'You' : 'Opponent'}: {hp} HP</span><small>{overview.data?.players.find(player => player.seat === seat)?.username}</small>{hp === 0 && <small>Keep destroyed</small>}</li>)}</ul>
    </section>}
    {result && <section className="result-play-again"><h2>Play again</h2><p>Start a new match or join a friend.</p><GameActions /></section>}
    <Link to="/">Back home</Link>
  </main>
}

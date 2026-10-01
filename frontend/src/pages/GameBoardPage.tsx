import PlanningTimer from '../components/PlanningTimer'
import CombatBoard from '../components/CombatBoard'
import { useLayoutEffect, useRef, useState } from 'react'
import * as api from '../api/gameApi'
import PlanningControls from '../components/PlanningControls'
import type { Action, Inspection, Selection } from '../components/PlanningControls'
import '../styles/game-board.css'
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
  const [selection, setSelection] = useState<Selection | null>(null)
  const [inspection, setInspection] = useState<Inspection>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [confirmLock, setConfirmLock] = useState(false)
  type Attempt = { gameId: string; round: number; key: string; action: Action }
  const [retry, setRetry] = useState<Attempt | null>(null)
  const inFlight = useRef(false)
  const context = useRef('')
  const identity = `${gameId}:${state?.currentRound}`
  useLayoutEffect(() => {
    context.current = identity
    return () => { context.current = '' }
  }, [identity])
  // Reset local interaction state before rendering a different round's snapshot.
  const [previousIdentity, setPreviousIdentity] = useState(identity)
  if (previousIdentity !== identity) {
    setPreviousIdentity(identity)
    setSelection(null); setInspection(null); setConfirmLock(false); setRetry(null); setError('')
  }
  if (selection && (!state?.yourUnits[selection.id] || state.yourUnits[selection.id].level !== selection.level || state.isLocked)) setSelection(null)
  if (inspection && !state?.yourUnits[inspection.id]) setInspection(null)
  if (confirmLock && !game.canMutate) setConfirmLock(false)
  const selected = selection && selection.gameId === gameId && selection.round === state?.currentRound && state.yourUnits[selection.id]?.level === selection.level ? selection.id : null
  const unavailable = game.isReconnecting ? 'Reconnecting…' : !state ? 'Loading game…'
    : state.isLocked ? 'Board locked' : state.state !== 'PREPARATION' ? `Actions unavailable during ${state.state}`
    : !game.canMutate ? 'Waiting for a successful state update' : ''
  const reason = unavailable || (pending ? 'Command in progress' : retry ? 'Retry the pending action or dismiss it first' : '')
  async function run(attempt: Attempt) {
    if (inFlight.current || unavailable || context.current !== `${attempt.gameId}:${attempt.round}`) return
    inFlight.current = true; setPending(true); setError(''); setRetry(null)
    const { gameId: id, round, key, action } = attempt
    try {
      const response = await (action.type === 'buy' ? api.buy(id, round, action.slot, key)
        : action.type === 'sell' ? api.sell(id, round, action.id, key)
        : action.type === 'refresh' ? api.refresh(id, round, key)
        : action.type === 'lock' ? api.lock(id, round, key)
        : api.relocate(id, round, { unitId: action.id, to: action.to }, key))
      const accepted = await game.onCommandSuccess(response)
      if (accepted && context.current === `${id}:${round}`) {
        if (action.type === 'sell' || action.type === 'lock') { setSelection(null); setInspection(null) }
      }
    } catch (failure) {
      if (context.current !== `${id}:${round}`) return
      if (failure instanceof ApiError && failure.status === 401) { auth.expire(); return }
      await game.onCommandError(failure)
      if (context.current !== `${id}:${round}`) return
      setError(failure instanceof ApiError ? failure.message : 'Could not complete the action.')
      if (failure instanceof ApiError && (failure.status === 0 || failure.status >= 500)) setRetry(attempt)
    } finally { inFlight.current = false; if (context.current) setPending(false) }
  }
  function act(action: Action) {
    if (!gameId || !state || reason || inFlight.current) return
    void run({ gameId, round: state.currentRound, key: api.newIdempotencyKey(), action })
  }
  if (!gameId) return <main><h1>Game not found</h1><Link to="/">Home</Link></main>
  return <main className="game-page"><Link to="/">Home</Link><h1>Game board</h1><p className="game-id">Game id: {gameId}</p>
    {game.isLoading && <p role="status">Loading game…</p>}
    {game.isReconnecting && <p role="alert">Reconnecting… Actions are disabled until the connection recovers.</p>}
    {game.error && !game.isReconnecting && <p role="alert">{game.error instanceof ApiError ? game.error.message : 'Could not load the game.'}</p>}
    {state && <section aria-label="Game status">
      <p>Round {state.currentRound}</p><p>Phase: {state.state}</p>
      <p>Gold: {state.yourGold}</p>
      <p className="phase-badge">{state.isLocked ? 'Board locked' : 'Board unlocked'}</p>
      <p>{state.opponentIsLocked ? 'Opponent locked' : 'Opponent not locked'}</p>
      {game.showDeadline && <PlanningTimer key={state.planningDeadline} deadline={state.planningDeadline!} />}
      <p>{game.canMutate ? 'Planning actions available.' : 'Planning actions unavailable.'}</p>
    </section>}
    {error && <p role="alert">{error}</p>}
    {retry && <div><button disabled={pending || !!unavailable} onClick={() => void run(retry)}>Retry same action</button><button disabled={pending} onClick={() => setRetry(null)}>Dismiss retry</button><p>The previous action may have succeeded. Retry uses its original request key.</p></div>}
    {state && (state.state === 'RESOLVING' || state.state === 'ROUND_RESULT' || state.state === 'LOCKED') && <CombatBoard state={state} />}
    {state && state.state === 'PREPARATION' && <PlanningControls state={state} selected={selected} inspected={inspection} reason={reason}
      onSelect={(unit) => setSelection({ id: unit.id, level: unit.level, gameId, round: state.currentRound })}
      onInspect={setInspection} onAction={act} onLock={() => setConfirmLock(true)} />}
    {confirmLock && <div role="dialog" aria-modal="false" aria-label="Confirm lock" className="lock-confirm">
      <p>Lock your board? You cannot change your formation for the rest of this round.</p>
      <button disabled={!!reason} onClick={() => { setConfirmLock(false); act({ type: 'lock' }) }}>Confirm lock</button>
      <button onClick={() => setConfirmLock(false)}>Cancel</button>
    </div>}
    {game.isResolving && <p role="status">Resolving…</p>}
    {result && <section aria-label="Last round result"><h2>Round {result.roundNumber} result</h2>
      <p>Outcome: {result.outcome} (PLAYER = seat 0; ENEMY = seat 1)</p>
      {Object.entries(result.keepDamage).map(([seat, damage]) => <p key={seat}>Seat {seat}: {damage} Keep damage; {result.keepHpAfter[seat]} HP remaining.</p>)}
    </section>}
    {game.roundResultError && <p role="alert">Could not load the last round result. Live game updates continue.</p>}
    <button onClick={auth.logout}>Log out</button>
  </main>
}

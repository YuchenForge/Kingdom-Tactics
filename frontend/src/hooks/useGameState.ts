import { useEffect, useLayoutEffect, useRef } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import { getRoundResult, getState, isWrongGameState, matchesActiveRound } from '../api/gameApi'
import type { CommandResult, GameState } from '../types'
import { useAuth } from './useAuth'

function hardFailure(error: unknown) {
  return error instanceof ApiError && [401, 403, 404].includes(error.status)
}
function notReady(error: unknown) {
  return error instanceof ApiError && error.status === 409 && error.code === 'GAME_NOT_READY'
}

export function useGameState(gameId: string | undefined) {
  const auth = useAuth()
  const navigate = useNavigate()
  const client = useQueryClient()
  const activeGame = useRef(gameId)
  const mounted = useRef(false)
  useLayoutEffect(() => {
    activeGame.current = gameId
    mounted.current = true
    return () => { mounted.current = false }
  }, [gameId])
  const queryKey = ['gameState', gameId] as const
  const query = useQuery({
    queryKey,
    queryFn: () => getState(gameId!),
    enabled: !!gameId,
    // The next poll is the retry. Surface failure immediately to disable commands.
    retry: false,
    refetchInterval: (q) => {
      if (hardFailure(q.state.error) || notReady(q.state.error)) return false
      if (q.state.data?.state === 'FINISHED' || q.state.data?.state === 'WAITING_FOR_PLAYERS') return false
      return 1000
    },
  })
  const state = query.data
  const round = state?.latestResolvedRound
  const resultQuery = useQuery({
    queryKey: ['roundResult', gameId, round],
    queryFn: () => getRoundResult(gameId!, round!),
    enabled: !!gameId && round != null && !hardFailure(query.error) && !notReady(query.error),
    staleTime: Infinity,
    retry: (count, error) => !hardFailure(error) && count < 1,
  })
  const handled = useRef('')
  useEffect(() => {
    const unauthorized = [query.error, resultQuery.error].some((error) => error instanceof ApiError && error.status === 401)
    const target = unauthorized ? 'auth'
      : notReady(query.error) || (!query.error && state?.state === 'WAITING_FOR_PLAYERS') ? 'lobby'
      : !query.error && state?.state === 'FINISHED' ? 'result' : ''
    const action = `${gameId}:${target}`
    if (!target || !gameId) { handled.current = ''; return }
    if (handled.current === action) return
    handled.current = action
    if (target === 'auth') auth.expire()
    else navigate(`/games/${encodeURIComponent(gameId)}${target === 'result' ? '/result' : ''}`, { replace: true })
  }, [query.error, resultQuery.error, state?.state, gameId, navigate, auth])

  function invalidate() {
    if (!mounted.current || !gameId || activeGame.current !== gameId) return Promise.resolve()
    return client.invalidateQueries({ queryKey, exact: true })
  }

  async function onCommandSuccess(result: CommandResult) {
    if (!mounted.current || !gameId || activeGame.current !== gameId) return false
    // Read the cache at completion, not the snapshot captured before the POST.
    const current = client.getQueryData<GameState>(queryKey)
    if (!current || !matchesActiveRound(result, { gameId, roundNumber: current.currentRound })) return false
    await invalidate()
    return true
  }

  async function onCommandError(error: unknown) {
    if (!isWrongGameState(error)) return false
    await invalidate()
    return true
  }

  return {
    state,
    phase: state?.state,
    roundResult: resultQuery.data,
    roundResultError: resultQuery.error,
    isLoading: !!gameId && query.isPending && !state,
    isReconnecting: query.isError && !hardFailure(query.error) && !notReady(query.error),
    error: query.error,
    canMutate: !!gameId && query.isSuccess && state?.state === 'PREPARATION' && !state.isLocked,
    isResolving: state?.state === 'RESOLVING',
    showDeadline: state?.state === 'PREPARATION' && state.planningDeadline != null,
    refetch: query.refetch,
    invalidate,
    onCommandSuccess,
    onCommandError,
  }
}

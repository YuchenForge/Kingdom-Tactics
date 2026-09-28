import { ApiError, apiRequest, assertPasswordUtf8Limit, setAuthToken } from './client'
import type {
  AuthResponse,
  BuyRequest,
  CommandIdentity,
  CommandResult,
  CommandSnapshot,
  GameOverview,
  GameState,
  MatchResult,
  RelocateRequest,
  RoundResult,
  SellRequest,
  UserProfile,
} from '../types'

export { clearAuthToken as logout } from './client'

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

/** True when a command reply still belongs to the round the UI is showing. */
export function matchesActiveRound(
  result: CommandIdentity,
  active: CommandIdentity,
): boolean {
  return result.gameId === active.gameId && result.roundNumber === active.roundNumber
}

/** The caller should refetch state on this error, without resubmitting the command. */
export function isWrongGameState(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 409 && error.code === 'WRONG_GAME_STATE'
}

export async function register(input: {
  username: string
  email: string
  password: string
}): Promise<AuthResponse> {
  assertPasswordUtf8Limit(input.password)
  const response = await apiRequest<AuthResponse>('/auth/register', {
    method: 'POST',
    body: input,
    auth: false,
  })
  setAuthToken(response.token)
  return response
}

export async function login(input: { email: string; password: string }): Promise<AuthResponse> {
  assertPasswordUtf8Limit(input.password)
  const response = await apiRequest<AuthResponse>('/auth/login', {
    method: 'POST',
    body: input,
    auth: false,
  })
  setAuthToken(response.token)
  return response
}

export function me(): Promise<UserProfile> {
  return apiRequest<UserProfile>('/me')
}

export function createGame(): Promise<GameOverview> {
  return apiRequest<GameOverview>('/games', { method: 'POST', body: {} })
}

export function joinGame(gameId: string): Promise<GameOverview> {
  return apiRequest<GameOverview>(`/games/${gameId}/join`, { method: 'POST' })
}

export function getGame(gameId: string): Promise<GameOverview> {
  return apiRequest<GameOverview>(`/games/${gameId}`)
}

export function getState(gameId: string): Promise<GameState> {
  return apiRequest<GameState>(`/games/${gameId}/state`)
}

export function getRoundResult(gameId: string, roundNumber: number): Promise<RoundResult> {
  return apiRequest<RoundResult>(`/games/${gameId}/rounds/${roundNumber}/result`)
}

export function getMatchResult(gameId: string): Promise<MatchResult> {
  return apiRequest<MatchResult>(`/games/${gameId}/result`)
}

/** One POST only: retries and state refetches belong to the caller. */
async function planningCommand(
  action: 'buy' | 'sell' | 'refresh' | 'relocate' | 'lock',
  gameId: string,
  roundNumber: number,
  idempotencyKey: string,
  body?: unknown,
): Promise<CommandResult> {
  const snapshot = await apiRequest<CommandSnapshot>(
    `/games/${gameId}/rounds/${roundNumber}/${action}`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
      body,
    },
  )
  return { ...snapshot, gameId, roundNumber }
}

/** Generate a key once per user action; reuse that key only when retrying that action. */
export function buy(
  gameId: string,
  roundNumber: number,
  shopSlot: number,
  key: string,
): Promise<CommandResult> {
  return planningCommand('buy', gameId, roundNumber, key, { shopSlot } satisfies BuyRequest)
}

export function sell(
  gameId: string,
  roundNumber: number,
  unitId: string,
  key: string,
): Promise<CommandResult> {
  return planningCommand('sell', gameId, roundNumber, key, { unitId } satisfies SellRequest)
}

export function refresh(gameId: string, roundNumber: number, key: string): Promise<CommandResult> {
  return planningCommand('refresh', gameId, roundNumber, key)
}

export function relocate(
  gameId: string,
  roundNumber: number,
  request: RelocateRequest,
  key: string,
): Promise<CommandResult> {
  return planningCommand('relocate', gameId, roundNumber, key, request)
}

export function lock(gameId: string, roundNumber: number, key: string): Promise<CommandResult> {
  return planningCommand('lock', gameId, roundNumber, key)
}

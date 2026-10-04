import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, AUTH_TOKEN_STORAGE_KEY } from './client'
import {
  getCombatEventsPage, buy, createGame, getGame, getMatchResult, getRoundResult, getState,
  isWrongGameState, joinGame, lock, login, logout, matchesActiveRound, me,
  newIdempotencyKey, refresh, register, relocate, sell,
} from './gameApi'
import type { CommandSnapshot } from '../types'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

const key = '11111111-1111-4111-8111-111111111111'
const snapshot: CommandSnapshot = {
  success: true, gold: 7, lane: [], board: [], units: {}, shop: [], isLocked: false,
}

describe('gameApi', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubEnv('VITE_API_BASE_URL', '/api')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('rejects oversize passwords before calling fetch', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    await expect(login({ email: 'a@b.com', password: 'a'.repeat(73) }))
      .rejects.toMatchObject({ code: 'VALIDATION_ERROR' })
    await expect(register({ username: 'player1', email: 'a@b.com', password: 'é'.repeat(37) }))
      .rejects.toMatchObject({ message: expect.stringContaining('72 UTF-8 bytes') })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each(['register', 'login'] as const)('%s omits Bearer, sends credentials, and stores the token', async (action) => {
    localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, 'old-token')
    const response = { userId: 'u1', username: 'player1', token: 'new-token' }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(response))
    vi.stubGlobal('fetch', fetchMock)
    const credentials = { email: 'a@b.com', password: 'password123' }
    const input = action === 'register' ? { ...credentials, username: 'player1' } : credentials
    const result = action === 'register'
      ? await register({ ...credentials, username: 'player1' })
      : await login(credentials)
    expect(result).toEqual(response)
    expect(localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)).toBe('new-token')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(`/api/auth/${action}`)
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).has('Authorization')).toBe(false)
    expect(JSON.parse(String(init.body))).toEqual(input)
  })

  it('logs out locally without a server request', () => {
    localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, 'jwt')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    logout()
    expect(localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)).toBeNull()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it.each([
    ['me', me, '/me', 'GET', undefined],
    ['create', createGame, '/games', 'POST', {}],
    ['join', () => joinGame('g'), '/games/g/join', 'POST', undefined],
    ['game', () => getGame('g'), '/games/g', 'GET', undefined],
    ['state', () => getState('g'), '/games/g/state', 'GET', undefined],
    ['round result', () => getRoundResult('g', 3), '/games/g/rounds/3/result', 'GET', undefined],
    ['match result', () => getMatchResult('g'), '/games/g/result', 'GET', undefined],
  ] as const)('maps %s to its authenticated endpoint', async (_name, call, path, method, body) => {
    const response = { marker: 'server-response' }
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(response))
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, 'jwt')
    expect(await call()).toEqual(response)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(`/api${path}`)
    expect(init.method).toBe(method)
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer jwt')
    expect(init.body).toBe(body === undefined ? undefined : JSON.stringify(body))
  })

  it.each([
    ['buy', () => buy('g', 3, 2, key), { shopSlot: 2 }],
    ['sell', () => sell('g', 3, 'u1', key), { unitId: 'u1' }],
    ['refresh', () => refresh('g', 3, key), undefined],
    ['lock', () => lock('g', 3, key), undefined],
    ['relocate', () => relocate('g', 3, { unitId: 'u1', to: { type: 'BOARD', x: 1, y: 3 } }, key),
      { unitId: 'u1', to: { type: 'BOARD', x: 1, y: 3 } }],
    ['relocate', () => relocate('g', 3, { unitId: 'u1', to: { type: 'LANE', slot: 4 } }, key),
      { unitId: 'u1', to: { type: 'LANE', slot: 4 } }],
  ] as const)('sends %s with the supplied key, exact body, and result identity', async (action, call, body) => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(snapshot))
    vi.stubGlobal('fetch', fetchMock)
    localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, 'jwt')
    expect(await call()).toEqual({ ...snapshot, gameId: 'g', roundNumber: 3 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe(`/api/games/g/rounds/3/${action}`)
    expect(init.method).toBe('POST')
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer jwt')
    expect(headers.get('Idempotency-Key')).toBe(key)
    expect(headers.get('Content-Type')).toBe(body === undefined ? null : 'application/json')
    expect(init.body).toBe(body === undefined ? undefined : JSON.stringify(body))
  })

  it('generates a key on explicit request and reuses it on a caller-initiated retry', async () => {
    const randomUUID = vi.spyOn(crypto, 'randomUUID').mockReturnValue(key)
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError('Connection lost'))
      .mockResolvedValueOnce(jsonResponse(snapshot))
    vi.stubGlobal('fetch', fetchMock)
    const actionKey = newIdempotencyKey()
    await expect(buy('g', 1, 0, actionKey)).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await expect(buy('g', 1, 0, actionKey)).resolves.toMatchObject({ gameId: 'g', roundNumber: 1 })
    expect(randomUUID).toHaveBeenCalledTimes(1)
    for (const [url, init] of fetchMock.mock.calls as [string, RequestInit][]) {
      expect(url).toBe('/api/games/g/rounds/1/buy')
      expect(new Headers(init.headers).get('Idempotency-Key')).toBe(key)
      expect(init.body).toBe(JSON.stringify({ shopSlot: 0 }))
    }
  })

  it('allows the caller to reject a delayed reply against the latest game and round', async () => {
    let resolveResponse!: (response: Response) => void
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>((resolve) => {
      resolveResponse = resolve
    })))
    let active = { gameId: 'g', roundNumber: 1 }
    const pending = buy(active.gameId, active.roundNumber, 0, key)
    active = { gameId: 'g', roundNumber: 2 }
    resolveResponse(jsonResponse(snapshot))
    const result = await pending
    expect(matchesActiveRound(result, active)).toBe(false)
    expect(matchesActiveRound(result, { gameId: 'other', roundNumber: 1 })).toBe(false)
    expect(matchesActiveRound(result, { gameId: 'g', roundNumber: 1 })).toBe(true)
  })

  it.each([
    [409, 'WRONG_GAME_STATE', true],
    [409, 'CONFLICT', false],
    [400, 'WRONG_GAME_STATE', false],
  ] as const)('preserves %s %s without refetching or resubmitting', async (status, code, expected) => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      error: code, message: 'Original server message', status, requestId: 'req-1',
    }, status))
    vi.stubGlobal('fetch', fetchMock)
    const error = await buy('g', 1, 0, key).catch((failure: unknown) => failure)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status, code, message: 'Original server message', requestId: 'req-1' })
    expect(isWrongGameState(error)).toBe(expected)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not identify unrelated values as wrong-state API errors', () => {
    for (const error of [null, undefined, new Error('failure'), { status: 409, code: 'WRONG_GAME_STATE' }]) {
      expect(isWrongGameState(error)).toBe(false)
    }
  })
})

it('loads an authorized event page and forwards cancellation', async () => {
  localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, 'jwt')
  const controller = new AbortController()
  const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ roundNumber: 2, events: [], nextAfterSequence: 200, hasMore: false, complete: true }))
  vi.stubGlobal('fetch', fetchMock)
  try {
    await getCombatEventsPage('g', 2, 200, controller.signal)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/games/g/events?round=2&afterSequence=200&limit=200')
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer jwt')
    expect(init.signal).toBe(controller.signal)
    controller.abort()
    fetchMock.mockRejectedValue(controller.signal.reason)
    await expect(getCombatEventsPage('g', 2, 200, controller.signal)).rejects.toHaveProperty('name', 'AbortError')
  } finally { vi.unstubAllGlobals(); localStorage.clear() }
})

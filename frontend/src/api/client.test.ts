import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  AUTH_TOKEN_STORAGE_KEY,
  apiRequest,
  assertPasswordUtf8Limit,
  clearAuthToken,
  getAuthToken,
  setAuthToken,
} from './client'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('client', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.stubEnv('VITE_API_BASE_URL', '/api')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('rejects passwords over 72 UTF-8 bytes', () => {
    expect(() => assertPasswordUtf8Limit('a'.repeat(73))).toThrow(ApiError)
    expect(() => assertPasswordUtf8Limit('é'.repeat(37))).toThrow(/72 UTF-8 bytes/)
    expect(() => assertPasswordUtf8Limit('a'.repeat(72))).not.toThrow()
  })

  it('stores and clears the JWT in localStorage', () => {
    setAuthToken('tok_abc')
    expect(localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)).toBe('tok_abc')
    expect(getAuthToken()).toBe('tok_abc')
    clearAuthToken()
    expect(getAuthToken()).toBeNull()
  })

  it('attaches Authorization: Bearer when a token is stored', async () => {
    setAuthToken('jwt-1')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/me')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer jwt-1')
  })

  it('omits Authorization when auth is false', async () => {
    setAuthToken('jwt-1')
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ token: 'x' }))
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/auth/login', { method: 'POST', body: {}, auth: false })

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBeNull()
  })

  it('maps HTTP error bodies to short user messages', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          { error: 'UNAUTHORIZED', message: 'Invalid email or password', status: 401 },
          401,
        ),
      ),
    )

    await expect(apiRequest('/me')).rejects.toMatchObject({
      status: 401,
      code: 'UNAUTHORIZED',
      message: 'Invalid email or password',
    })
  })

  it('uses a fallback message when the error body has no message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 500 })))

    await expect(apiRequest('/me')).rejects.toMatchObject({
      status: 500,
      message: 'Something went wrong. Try again.',
    })
  })

  it('maps a failed fetch to a network error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(apiRequest('/me')).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      message: 'Could not reach the server.',
    })
  })

  it.each([200, 204])('returns undefined for empty %s responses', async (status) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status })))
    await expect(apiRequest('/me')).resolves.toBeUndefined()
  })

  it.each(['/api/', ''])('normalizes the API base %j', async (base) => {
    vi.stubEnv('VITE_API_BASE_URL', base)
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({}))
    vi.stubGlobal('fetch', fetchMock)
    await apiRequest('/me')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/me')
  })

  it('preserves WRONG_GAME_STATE error details', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      error: 'WRONG_GAME_STATE', message: 'Round advanced', requestId: 'req-1',
    }, 409)))
    await expect(apiRequest('/games/g/rounds/1/lock', { method: 'POST' })).rejects.toMatchObject({
      status: 409, code: 'WRONG_GAME_STATE', message: 'Round advanced', requestId: 'req-1',
    })
  })

  it('uses the status fallback for non-JSON error responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>Unavailable</html>', { status: 500 })))
    await expect(apiRequest('/me')).rejects.toMatchObject({
      status: 500, code: 'HTTP_ERROR', message: 'Something went wrong. Try again.',
    })
  })

})

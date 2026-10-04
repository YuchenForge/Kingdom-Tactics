import type { ApiErrorBody } from '../types'

export const AUTH_TOKEN_STORAGE_KEY = 'kt.authToken'
export const MAX_PASSWORD_UTF8_BYTES = 72

// Fallback error messages
const FALLBACK_MESSAGES: Record<number, string> = {
  400: 'That request was invalid.',
  401: 'Please sign in again.',
  403: 'You cannot do that in this game.',
  404: 'Not found.',
  409: 'The game changed. Refresh and try again.',
  423: 'Your board is locked.',
  429: 'Too many requests. Wait a moment.',
  500: 'Something went wrong. Try again.',
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId?: string | null

  constructor(status: number, code: string, message: string, requestId?: string | null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.requestId = requestId
  }
}

export function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length
}

export function assertPasswordUtf8Limit(password: string): void {
  if (utf8ByteLength(password) > MAX_PASSWORD_UTF8_BYTES) {
    throw new ApiError(
      400,
      'VALIDATION_ERROR',
      'Password must be at most 72 UTF-8 bytes.',
    )
  }
}

// Read JWT
export function getAuthToken(): string | null {
  return localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)
}

// Store JWT
export function setAuthToken(token: string): void {
  localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, token)
}

/** MVP logout: discard the JWT. There is no server logout endpoint. */
export function clearAuthToken(): void {
  localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY)
}

// Backend URL
function apiBaseUrl(): string {
  const raw = import.meta.env.VITE_API_BASE_URL
  const base = typeof raw === 'string' && raw.length > 0 ? raw : '/api'
  return base.replace(/\/+$/, '')
}

function userMessage(status: number, body: ApiErrorBody | null): string {
  if (body?.message && body.message.trim().length > 0) {
    return body.message
  }
  return FALLBACK_MESSAGES[status] ?? 'Something went wrong. Try again.'
}

async function parseErrorBody(response: Response): Promise<ApiErrorBody | null> {
  const text = await response.text()
  if (text.length === 0) {
    return null
  }
  try {
    return JSON.parse(text) as ApiErrorBody
  } catch {
    return null
  }
}

export type ApiRequestOptions = {
  method?: 'GET' | 'POST'
  body?: unknown
  headers?: Record<string, string>
  /** Defaults to true. Set false for register/login. */
  auth?: boolean
  signal?: AbortSignal
}

export async function apiRequest<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  // Determine HTTP method
  const method = options.method ?? 'GET'
  // Create headers
  const headers = new Headers(options.headers)
  if (options.body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }

  // Attach the JWT
  const useAuth = options.auth !== false
  if (useAuth) {
    const token = getAuthToken()
    if (token) {
      headers.set('Authorization', `Bearer ${token}`)
    }
  }

  // Send the HTTP request
  let response: Response
  try {
    response = await fetch(`${apiBaseUrl()}${path}`, {
      method,
      headers,
      signal: options.signal,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch (error) {
    if (options.signal?.aborted || (error instanceof DOMException && error.name === 'AbortError')) throw error
    throw new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server.')
  }

  // HTTP error responses
  if (!response.ok) {
    const body = await parseErrorBody(response)
    throw new ApiError(
      response.status,
      body?.error ?? 'HTTP_ERROR',
      userMessage(response.status, body),
      body?.requestId,
    )
  }

  if (response.status === 204) {
    return undefined as T
  }

  const text = await response.text()
  if (text.length === 0) {
    return undefined as T
  }
  return JSON.parse(text) as T
}

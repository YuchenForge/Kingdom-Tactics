import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useSyncExternalStore } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { ApiError, getAuthToken } from '../api/client'
import * as api from '../api/gameApi'
import { authPath } from '../lib/navigation'

const sessionEvent = 'kt-session-changed'

// Subscribe to authentication/session changes.
// - sessionEvent handles changes made in the current tab.
// - storage handles localStorage changes made in other tabs.
function subscribe(listener: () => void) {
  window.addEventListener(sessionEvent, listener)
  window.addEventListener('storage', listener)
  return () => {
    window.removeEventListener(sessionEvent, listener)
    window.removeEventListener('storage', listener)
  }
}

// Notify components using useAuth() that the stored session changed.
function notifySession() { window.dispatchEvent(new Event(sessionEvent)) }

export function useAuth() {
  // Storage is the token source; the external-store subscription only triggers renders.
  const token = useSyncExternalStore(subscribe, getAuthToken, () => null)
  const client = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  // If a token exists, ask the backend for the current user.
  const query = useQuery({
    queryKey: ['me'], queryFn: api.me, enabled: !!token,
    // A 401 means the token is invalid/expired
    retry: (count, error) => !(error instanceof ApiError && error.status === 401) && count < 1,
  })
  // A 401 from /me means the stored session is no longer valid.
  const unauthorized = query.error instanceof ApiError && query.error.status === 401

  // Completely end the current session and redirect the user.
  function endSession(destination: string) {
    api.logout()
    // Discard session-bound data so another login cannot see the previous user's cache.
    client.clear()
    notifySession()
    navigate(destination, { replace: true })
  }

  // Automatically clean up the session if /me rejects the token.
  useEffect(() => {
    if (token && unauthorized && getAuthToken() === token) {
      api.logout()
      client.clear()
      notifySession()
      const path = location.pathname === '/auth'
        ? location.pathname + location.search
        : authPath(location.pathname + location.search)
      navigate(path, { replace: true })
    }
  }, [token, unauthorized, client, navigate, location.pathname, location.search])

  // Handle both registration and login
  async function authenticate(input: { email: string; password: string; username?: string }) {
    if (input.username !== undefined) {
      await api.register({ ...input, username: input.username })
    } else {
      await api.login({ email: input.email, password: input.password })
    }
    client.removeQueries({ queryKey: ['me'] })
    notifySession()
    await client.invalidateQueries({ queryKey: ['me'] })
  }

  return {
    token: unauthorized ? null : token,
    user: token && !unauthorized ? query.data : undefined,
    isLoading: !!token && query.isPending,
    error: query.error,
    retry: query.refetch,
    authenticate,
    logout: () => endSession('/'),
    expire: () => endSession(authPath(location.pathname + location.search)),
  }
}

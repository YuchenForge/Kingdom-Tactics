import { Navigate, Outlet, useLocation } from 'react-router'
import { useAuth } from '../hooks/useAuth'
import { authPath } from '../lib/navigation'

export default function RequireAuth() {
  const auth = useAuth()
  const location = useLocation()
  if (!auth.token) return <Navigate to={authPath(location.pathname + location.search)} replace />
  if (auth.isLoading) return <p role="status">Checking your session…</p>
  if (auth.error) return <main><p role="alert">Could not check your session. Please try again.</p><button onClick={() => void auth.retry()}>Try again</button><button onClick={auth.logout}>Log out</button></main>
  return <Outlet />
}

import { useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import { useAuth } from '../hooks/useAuth'
import { safeNext } from '../lib/navigation'

export default function AuthPage() {
  const [params] = useSearchParams()
  const [registering, setRegistering] = useState(params.get('mode') === 'register')
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [error, setError] = useState('')
  const auth = useAuth()
  const navigate = useNavigate()
  return <main><Link to="/">Kingdom Tactics</Link><h1>{registering ? 'Create account' : 'Log in'}</h1>
    <form onSubmit={async (event) => {
      event.preventDefault()
      if (busyRef.current) return
      busyRef.current = true; setBusy(true); setError('')
      try {
        await auth.authenticate({ email, password, ...(registering ? { username } : {}) })
        setPassword('')
        navigate(safeNext(params.get('next')), { replace: true })
      } catch (failure) {
        setError(failure instanceof ApiError ? failure.message : 'Could not sign in. Please try again.')
      } finally { busyRef.current = false; setBusy(false) }
    }}>
      {registering && <label>Username<input autoComplete="username" value={username} minLength={3} maxLength={50} required onChange={(e) => setUsername(e.target.value)} /></label>}
      <label>Email<input type="email" autoComplete="email" value={email} required onChange={(e) => setEmail(e.target.value)} /></label>
      <label>Password<input type="password" autoComplete={registering ? 'new-password' : 'current-password'} value={password} minLength={registering ? 8 : undefined} required onChange={(e) => setPassword(e.target.value)} /></label>
      {registering && <p>At least 8 characters, at most 72 UTF-8 bytes.</p>}
      {error && <p role="alert">{error}</p>}
      <button disabled={busy} type="submit">{busy ? 'Please wait…' : registering ? 'Register' : 'Log in'}</button>
    </form>
    <button disabled={busy} onClick={() => { setRegistering(!registering); setError('') }}>{registering ? 'Use an existing account' : 'Create an account'}</button>
  </main>
}

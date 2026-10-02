import { Link } from 'react-router'
import { useAuth } from '../hooks/useAuth'
import GameActions from '../components/GameActions'

export default function LandingPage() {
  const auth = useAuth()
  return <main className="landing-page">
    <img className="landing-logo" src="/assets/brand/logo-mark.svg" alt="" />
    <p className="eyebrow">A duel of strategy</p><h1>Kingdom Tactics</h1>
    <p>Build your army, place your units, and lock your formation. Combat resolves automatically. Protect your Keep and outlast your opponent.</p>
    {auth.user ? <><p>Welcome, {auth.user.username}.</p><GameActions split /><button className="text-action" onClick={auth.logout}>Log out</button></>
      : auth.isLoading ? <p role="status">Checking your session…</p>
      : <nav><Link to="/auth">Log in</Link><Link className="primary-action" to="/auth?mode=register">Create account</Link></nav>}
    {auth.error && auth.token && <p role="alert">Could not load your profile. <button onClick={() => void auth.retry()}>Try again</button></p>}
  </main>
}

import { Link, Route, Routes } from 'react-router'
import RequireAuth from './components/RequireAuth'
import LandingPage from './pages/LandingPage'
import AuthPage from './pages/AuthPage'
import JoinPage from './pages/JoinPage'
import LobbyPage from './pages/LobbyPage'
import GameBoardPage from './pages/GameBoardPage'
import ResultPage from './pages/ResultPage'

export default function App() {
  return <Routes>
    <Route path="/" element={<LandingPage />} />
    <Route path="/auth" element={<AuthPage />} />
    <Route element={<RequireAuth />}>
      <Route path="/join/:gameId" element={<JoinPage />} />
      <Route path="/games/:gameId" element={<LobbyPage />} />
      <Route path="/games/:gameId/play" element={<GameBoardPage />} />
      <Route path="/games/:gameId/result" element={<ResultPage />} />
    </Route>
    <Route path="*" element={<main><h1>Page not found</h1><Link to="/">Back home</Link></main>} />
  </Routes>
}

import { Link, useParams } from 'react-router'
import GameActions from '../components/GameActions'
export default function ResultPage() {
  const { gameId } = useParams()
  return <main><h1>Match result</h1><p>Game id: {gameId}</p><p>Match details will appear here when the results UI is built.</p><GameActions /><Link to="/">Back home</Link></main>
}

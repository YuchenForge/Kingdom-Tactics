import { Link, useParams } from 'react-router'
export default function GameBoardPage() {
  const { gameId } = useParams()
  return <main><h1>Game board</h1><p>Game id: {gameId}</p><p>Your game is ready. Planning controls are coming in the next slice.</p><Link to="/">Back home</Link></main>
}

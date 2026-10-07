import type { RoundResult } from '../types'
import { roundOutcomeLabel } from '../combat/roundFlow'

export default function RoundResultSummary({ result, seat, current }: { result: RoundResult; seat: number; current: boolean }) {
  return <section className={`round-summary ${current ? 'current-round-summary' : ''}`} aria-label="Last round result">
    <div><h2>Round {result.roundNumber} result</h2><p role={current ? 'status' : undefined}>{roundOutcomeLabel(result.outcome, seat)}</p></div>
    <div className="round-keep-results">{[seat, 1 - seat].map(player => <p key={player}>
      <strong>{player === seat ? 'You' : 'Opponent'}</strong>: {result.keepDamage[String(player)]} Keep damage; {result.keepHpAfter[String(player)]} HP remaining.
    </p>)}</div>
  </section>
}

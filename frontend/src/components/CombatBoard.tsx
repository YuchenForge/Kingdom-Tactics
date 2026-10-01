import type { GameState } from '../types'

export default function CombatBoard({ state }: { state: GameState }) {
  // Server snapshots use global x=0..3, y=0..7. Display horizontally with our seat on the left.
  const units = state.combatUnits ?? []
  return <section className="combat-frame" aria-label="Merged combat board">
    <h2>Round {state.currentRound} · Combat</h2>
    <p role="status">{state.state === 'ROUND_RESULT' ? 'Combat complete' : 'Resolving…'}</p>
    <div className="combat-field">
      <aside className="keep friendly-keep"><img src="/assets/keep/keep.svg" alt="" /><strong>Your Keep</strong><span>{state.yourKeepHp} HP</span></aside>
      <div className="merged-grid" role="grid" aria-label="Eight columns by four rows">
        {Array.from({ length: 4 }, (_, row) => <div className="merged-row" role="row" key={row}>
          {Array.from({ length: 8 }, (_, col) => {
            const x = state.yourSeat === 1 ? row : 3 - row
            const y = state.yourSeat === 1 ? 7 - col : col
            const unit = units.find((item) => item.x === x && item.y === y)
            const side = unit?.seat === state.yourSeat ? 'friendly' : 'enemy'
            return <div className={`combat-cell ${unit ? side : ''}`} role="gridcell" key={col}
              aria-label={`Global ${x},${y}: ${unit ? `${unit.type}, level ${unit.level}, ${side}` : 'empty'}`}>
              {unit && <><img src={`/assets/units/${unit.type.toLowerCase()}.svg`} alt={unit.type} /><span className="unit-level">{'★'.repeat(unit.level)}</span></>}
            </div>
          })}
        </div>)}
      </div>
      <aside className="keep opponent-keep"><img src="/assets/keep/keep.svg" alt="" /><strong>Opponent Keep</strong><span>{state.opponentKeepHp} HP</span></aside>
    </div>
  </section>
}

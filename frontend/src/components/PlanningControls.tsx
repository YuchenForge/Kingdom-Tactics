import { abilityDescription } from '../lib/abilityDescription'
import InterfaceIcon from './InterfaceIcon'
import UnitPopup from './UnitPopup'
import { useEffect, useRef, useState } from 'react'
import type { RelocateDestination, UnitView, GameState } from '../types'

export type Selection = { id: string; level: number; gameId: string; round: number }
export type Inspection = { id: string; mode: 'hover' | 'inspect' } | null
export type Action = { type: 'buy'; slot: number } | { type: 'sell'; id: string } | { type: 'refresh' } | { type: 'lock' } | { type: 'relocate'; id: string; to: RelocateDestination }

type Props = {
  state: GameState; selected: string | null; inspected: Inspection; reason: string;
  onSelect: (unit: UnitView) => void; onInspect: (value: Inspection) => void;
  onAction: (action: Action) => void; onLock: () => void;
}

export default function PlanningControls({ state, selected, inspected, reason, onSelect, onInspect, onAction, onLock }: Props) {
  const [shopOpen, setShopOpen] = useState(true)
  const art = (type: string) => ['squire', 'shieldbearer', 'ranger', 'knight', 'mage', 'healer'].includes(type.toLowerCase()) ? `/assets/units/${type.toLowerCase()}.svg` : undefined
  const deployed = state.yourBoard.flat().filter(Boolean).length
  const occupied = state.yourLane.filter((slot) => slot.unitId != null).length
  const cap = state.currentRound < 5 ? 3 : 5
  const onBoard = selected != null && state.yourBoard.some((row) => row.includes(selected))
  const destinationReason = reason || (!selected ? 'Select a unit first' : '')
  return <div className={`planning-controls ${shopOpen ? 'shop-open' : 'shop-closed'}`}>
    <aside className="keep friendly-keep"><img src="/assets/keep/keep.svg" alt="" /><strong>Your Keep</strong><span>{state.yourKeepHp} HP</span></aside>
    <aside className="keep opponent-keep"><img src="/assets/keep/keep.svg" alt="" /><strong>Opponent Keep</strong><span>{state.opponentKeepHp} HP</span><small>{state.opponentIsLocked ? 'Locked' : 'Not locked'}</small></aside>
    <section className="recruit-panel" aria-label="Shop" hidden={!shopOpen}><div className="shop-heading"><h2>Recruit</h2><button aria-label="Close shop" onClick={() => setShopOpen(false)}>×</button></div><div className="shop-grid">
      {Array.from({ length: 3 }, (_, slot) => {
        const offer = state.shop.find((item) => item.slot === slot)
        const disabled = reason || (!offer?.unitType ? 'Offer already sold' : state.yourGold < offer.cost ? 'Not enough gold' : occupied >= 5 ? 'Lane full' : '')
        return <article className="shop-card" key={slot}>
          <h3>{offer?.unitType || 'Sold'}</h3>{offer?.unitType && art(offer.unitType) && <img className="offer-art" src={art(offer.unitType)} alt="" />}
          {offer?.unitType && <><p>{offer.cost} gold</p><p>HP {offer.maxHp} · Attack {offer.attack} · Range {offer.range}</p><p>{abilityDescription(offer.specialAbility, offer.healAmount)}</p></>}
          <button disabled={!!disabled} aria-describedby={`shop-reason-${slot}`} onClick={() => onAction({ type: 'buy', slot })}>Buy slot {slot + 1}</button>
          <small id={`shop-reason-${slot}`}>{disabled}</small>
        </article>
      })}
    </div><button disabled={!!reason || state.yourGold < 1} onClick={() => onAction({ type: 'refresh' })}>Refresh shop · 1 gold</button>
      <small>{reason || (state.yourGold < 1 ? 'Not enough gold' : '')}</small>
    </section>
    <section className="holding-lane" aria-label="Holding lane"><div className="lane-row"><h2>Holding lane · {occupied} / 5</h2><div className="lane-grid">
      {Array.from({ length: 5 }, (_, slot) => {
        const id = state.yourLane.find((item) => item.slot === slot)?.unitId
        const unit = id ? state.yourUnits[id] : undefined
        return <div className="position" key={slot}>{unit ? <PlanningUnitTile unit={unit} selected={selected} inspected={inspected} reason={reason} onSelect={onSelect} onInspect={onInspect} onAction={onAction} /> : <button className={!id && !destinationReason ? 'destination legal' : 'destination'}
          disabled={!!id || !!destinationReason} aria-label={`Lane slot ${slot + 1}`} title={destinationReason}
          onClick={() => selected && onAction({ type: 'relocate', id: selected, to: { type: 'LANE', slot } })}>{id ? 'Loading unit…' : 'Empty'}</button>}</div>
      })}
    </div><div className="formation-actions"><button onClick={() => setShopOpen(!shopOpen)} aria-expanded={shopOpen}><InterfaceIcon name="unit-cap" />Recruit</button><button disabled={!!reason} onClick={onLock}><InterfaceIcon name="lock" />Lock board</button><small>{reason}</small></div></div>
      {selected && !onBoard && deployed >= cap && <p className="board-cap-message">Board at cap. Move a deployed unit to the lane or sell one first.</p>}
    </section>
    <section className="formation" aria-label="Your board"><h2>Deployed · {deployed} / {cap}</h2>
      <p className="placement-hint">{reason || (selected ? 'Choose a highlighted destination. Moving costs no gold.' : 'Select a unit, then choose an empty destination.')}</p>
      <div className="board-grid" role="grid" aria-label="Placement board">
        {Array.from({ length: 4 }, (_, y) => <div role="row" className="board-row" key={y}>
          {Array.from({ length: 4 }, (_, x) => {
            const id = state.yourBoard[y]?.[x]
            const unit = id ? state.yourUnits[id] : undefined
            const blocked = destinationReason || (!onBoard && deployed >= cap ? 'Board at cap' : '')
            return <div role="gridcell" className="position" key={x}>{unit ? <PlanningUnitTile unit={unit} selected={selected} inspected={inspected} reason={reason} onSelect={onSelect} onInspect={onInspect} onAction={onAction} /> : <button
              className={!id && !blocked ? 'destination legal' : 'destination'} disabled={!!id || !!blocked}
              aria-label={`Board x ${x} y ${y}`} title={blocked}
              onClick={() => selected && onAction({ type: 'relocate', id: selected, to: { type: 'BOARD', x, y } })}>{id ? 'Loading unit…' : 'Empty'}</button>}</div>
          })}
        </div>)}
      </div>
    </section>

  </div>
}

function PlanningUnitTile({ unit, selected, inspected, reason, onSelect, onInspect, onAction }: Pick<Props, 'selected' | 'inspected' | 'reason' | 'onSelect' | 'onInspect' | 'onAction'> & { unit: UnitView }) {
    const anchor = useRef<HTMLDivElement>(null)
    const closing = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
    const cancelClose = () => clearTimeout(closing.current)
    const leave = () => { if (inspected?.mode === 'hover') closing.current = setTimeout(() => onInspect(null), 180) }
    useEffect(() => () => clearTimeout(closing.current), [])
    const art = (type: string) => `/assets/units/${type.toLowerCase()}.svg`

    const opened = inspected?.id === unit.id
    return <div ref={anchor} className="unit-tile" onPointerEnter={(event) => {
      cancelClose(); if (event.pointerType === 'mouse') onInspect({ id: unit.id, mode: 'hover' })
    }} onPointerLeave={leave} onKeyDown={(event) => {
      if (event.key === 'Escape') onInspect(null)
    }}>
      <button className={selected === unit.id ? 'unit selected' : 'unit'} aria-pressed={selected === unit.id}
        aria-label={`Select ${unit.unitType} ${unit.id}`} onClick={() => { onInspect(null); onSelect(unit) }}>
        {art(unit.unitType) ? <img src={art(unit.unitType)} alt={unit.unitType} /> : unit.unitType}<span className="unit-level" aria-label={`Level ${unit.level}`}><span className="visually-hidden">Level {unit.level}</span><span aria-hidden="true">{'★'.repeat(unit.level)}</span></span>
      </button>
      <button className="inspect" aria-label={`Inspect ${unit.unitType} ${unit.id}`} aria-expanded={opened}
        onClick={() => onInspect(opened ? null : { id: unit.id, mode: 'inspect' })}>Inspect</button>
      {opened && <UnitPopup anchor={anchor} label={`${unit.unitType} details`} onEnter={cancelClose} onLeave={leave} onClose={() => onInspect(null)}>
        <div className="popup-heading"><strong>{unit.unitType}</strong><span>Level {unit.level}</span></div>
        <div className="popup-stats"><span><InterfaceIcon name="keep-hp" />HP <b>{unit.maxHp}</b></span><span>ATK <b>{unit.attack}</b></span><span>Range <b>{unit.range}</b></span></div>
        <p>{abilityDescription(unit.specialAbility, unit.healAmount)}</p>
        <p className="popup-note">Full HP at combat start</p>
        <span className="visually-hidden">Sell refund: {unit.sellRefund} gold</span>
        <div className="popup-actions"><button className="sell-action" aria-label={`Sell for ${unit.sellRefund} gold`} disabled={!!reason} onClick={() => onAction({ type: 'sell', id: unit.id })}><InterfaceIcon name="sell" />Sell · +{unit.sellRefund} gold</button><button aria-label="Close details" onClick={() => onInspect(null)}>Close</button></div>
        {reason && <small>{reason}</small>}
      </UnitPopup>}
    </div>
  }

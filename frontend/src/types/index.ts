/** Game lifecycle phases from GET /games and GET /state. */
export type GamePhase =
  | 'WAITING_FOR_PLAYERS'
  | 'PREPARATION'
  | 'LOCKED'
  | 'RESOLVING'
  | 'ROUND_RESULT'
  | 'FINISHED'

/** Seat-absolute combat outcome (PLAYER_* = seat 0, ENEMY_* = seat 1). */
export type CombatOutcome = 'PLAYER_VICTORY' | 'ENEMY_VICTORY' | 'TIME_LIMIT' | 'DRAW'

export type AuthResponse = {
  userId: string
  username: string
  email?: string
  token: string
  createdAt?: string
  expiresIn?: number
}

export type UserProfile = {
  userId: string
  username: string
  email: string
  rating: number
  createdAt: string
}

export type PlayerSummary = {
  playerId: string
  username: string
  keepHp: number
  gold: number
  seat: number
}

export type GameOverview = {
  gameId: string
  state: GamePhase
  currentRound: number
  players: PlayerSummary[]
  planningDeadline?: string | null
  createdAt?: string | null
  startedAt?: string | null
}

/**
 * Server-authored unit view. Display stats and sellRefund come from the API;
 * do not keep a client cost or balance table.
 */
export type UnitView = {
  id: string
  unitType: string
  level: number
  maxHp: number
  attack: number
  range: number
  specialAbility: string
  healAmount: number
  sellRefund: number
}

export type LaneSlot = {
  slot: number
  unitId: string | null
  unitType: string | null
  level: number | null
}

export type ShopSlot = {
  slot: number
  unitType: string | null
  cost: number
  maxHp: number
  attack: number
  range: number
  specialAbility: string | null
  healAmount: number
}

/** 4×4 grid of unit ids (`yourBoard[y][x]`). */
export type BoardGrid = (string | null)[][]

export type CombatUnit = { id: string; type: string; level: number; seat: number; x: number; y: number }

export type GameState = {
  combatUnits?: CombatUnit[]

  gameId: string
  state: GamePhase
  currentRound: number
  latestResolvedRound: number | null
  yourSeat: number
  yourKeepHp: number
  yourGold: number
  opponentKeepHp: number
  opponentUnitCount: number
  yourBoard: BoardGrid
  yourUnits: Record<string, UnitView>
  yourLane: LaneSlot[]
  shop: ShopSlot[]
  planningDeadline: string | null
  isLocked: boolean
  opponentIsLocked: boolean
}

export type CommandSnapshot = {
  success: boolean
  gold: number
  lane: LaneSlot[]
  board: BoardGrid
  units: Record<string, UnitView>
  shop: ShopSlot[]
  isLocked: boolean
  opponentIsLocked?: boolean | null
  message?: string | null
  nextState?: string | null
}

export type BuyRequest = {
  shopSlot: number
}

export type SellRequest = {
  unitId: string
}

export type RelocateToBoard = {
  type: 'BOARD'
  x: number
  y: number
}

export type RelocateToLane = {
  type: 'LANE'
  slot: number
}

export type RelocateDestination = RelocateToBoard | RelocateToLane

export type RelocateRequest = {
  unitId: string
  to: RelocateDestination
}

export type SurvivingUnit = {
  id: string
  type: string
  level: number
  x: number
  y: number
  currentHp: number
  maxHp: number
}

export type EndSnapshot = {
  survivors: SurvivingUnit[]
  keepHp: number
}

export type RoundResult = {
  roundNumber: number
  outcome: CombatOutcome
  keepDamage: Record<string, number>
  keepHpAfter: Record<string, number>
  endSnapshots: Record<string, EndSnapshot>
}

export type MatchResult = {
  gameId: string
  state: 'FINISHED'
  winnerId: string | null
  winnerUsername: string | null
  loserUsername: string | null
  finalKeepHp: number[]
  finalRound: number
  durationSeconds: number
  finishedAt: string
}

export type ApiErrorBody = {
  error: string
  message: string
  status: number
  timestamp?: string
  requestId?: string | null
}

export type CommandIdentity = {
  gameId: string
  roundNumber: number
}

export type CommandResult = CommandSnapshot & CommandIdentity

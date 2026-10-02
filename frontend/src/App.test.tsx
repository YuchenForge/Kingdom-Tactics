import '@testing-library/jest-dom/vitest'
import { StrictMode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import * as api from './api/gameApi'
import { ApiError, setAuthToken, getAuthToken, clearAuthToken } from './api/client'
import { safeNext, inviteDestination } from './lib/navigation'
import type { GameOverview, GameState } from './types'

vi.mock('./api/gameApi', async (importOriginal) => ({
  ...await importOriginal<typeof import('./api/gameApi')>(),
  me: vi.fn(), login: vi.fn(), register: vi.fn(), logout: vi.fn(),
  createGame: vi.fn(), joinGame: vi.fn(), getGame: vi.fn(), getState: vi.fn(), getRoundResult: vi.fn(), getMatchResult: vi.fn(),
}))
const user = { userId: 'u1', username: 'Alice', email: 'a@example.com', rating: 1000, createdAt: '' }
const game: GameOverview = { gameId: 'g1', state: 'WAITING_FOR_PLAYERS', currentRound: 1,
  players: [{ playerId: 'u1', username: 'Alice', keepHp: 20, gold: 10, seat: 0 }] }
const state: GameState = {
  gameId: 'g1', state: 'PREPARATION', currentRound: 1, latestResolvedRound: null,
  yourSeat: 0, yourKeepHp: 20, yourGold: 10, opponentKeepHp: 20, opponentUnitCount: 0,
  yourBoard: [], yourUnits: {}, yourLane: [], shop: [], planningDeadline: '2026-09-30T12:00:00Z',
  isLocked: false, opponentIsLocked: false,
}
const clients: QueryClient[] = []
function Location() { const location = useLocation(); return <output data-testid="location">{location.pathname}{location.search}</output> }
function renderApp(path = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false }, mutations: { retry: false } } })
  clients.push(client)
  render(<StrictMode><QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><App /><Location /></MemoryRouter></QueryClientProvider></StrictMode>)
  return client
}
function submitAuth() {
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'a@example.com' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password123' } })
  fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
}
beforeEach(() => {
  localStorage.clear()
  vi.resetAllMocks()
  vi.mocked(api.me).mockResolvedValue(user)
  vi.mocked(api.logout).mockImplementation(clearAuthToken)
  vi.mocked(api.login).mockImplementation(async () => {
    setAuthToken('jwt'); return { userId: 'u1', username: 'Alice', token: 'jwt' }
  })
  vi.mocked(api.register).mockImplementation(async () => {
    setAuthToken('jwt'); return { userId: 'u1', username: 'Alice', token: 'jwt' }
  })
  vi.mocked(api.getMatchResult).mockResolvedValue({ gameId: 'g1', state: 'FINISHED', winnerId: 'u1', winnerUsername: 'Alice', loserUsername: 'Bob', finalKeepHp: [12, 0], finalRound: 3, durationSeconds: 125, finishedAt: '2026-10-01T12:00:00Z' })
  vi.mocked(api.getGame).mockResolvedValue(game)
  vi.mocked(api.getState).mockResolvedValue(state)
  vi.mocked(api.createGame).mockResolvedValue(game)
  vi.mocked(api.joinGame).mockResolvedValue({ ...game, state: 'PREPARATION' })
})
afterEach(() => { cleanup(); clients.splice(0).forEach((client) => client.clear()); vi.unstubAllGlobals() })

describe('auth and lobby routes', () => {
  it('renders the public landing page', () => {
    renderApp()
    expect(screen.getByRole('heading', { name: 'Kingdom Tactics' })).toBeInTheDocument()
    expect(api.me).not.toHaveBeenCalled()
  })
  it('preserves protected pathname and query in next', async () => {
    renderApp('/games/g1/play?view=mine')
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/auth?next=%2Fgames%2Fg1%2Fplay%3Fview%3Dmine'))
  })
  it.each(['login', 'register'])('preserves an invite through %s and joins once in Strict Mode', async (mode) => {
    renderApp('/join/g1')
    await screen.findByRole('heading', { name: 'Log in' })
    if (mode === 'register') {
      fireEvent.click(screen.getByRole('button', { name: 'Create an account' }))
      fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'Alice' } })
      fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'a@example.com' } })
      fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'password123' } })
      fireEvent.click(screen.getByRole('button', { name: 'Register' }))
    } else submitAuth()
    await screen.findByRole('heading', { name: 'Game board' })
    expect(api.joinGame).toHaveBeenCalledExactlyOnceWith('g1')
    expect(screen.getByTestId('location')).toHaveTextContent('/games/g1/play')
  })
  it('shows auth errors and permits another attempt', async () => {
    vi.mocked(api.login).mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'Invalid email or password'))
    renderApp('/auth'); submitAuth()
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
    expect(screen.getByRole('button', { name: 'Log in' })).toBeEnabled()
  })
  it('prevents duplicate auth submission while pending', async () => {
    vi.mocked(api.login).mockReturnValue(new Promise(() => {}))
    renderApp('/auth'); submitAuth()
    expect(screen.getByRole('button', { name: 'Please wait…' })).toBeDisabled()
    fireEvent.submit(screen.getByLabelText('Email').closest('form')!)
    expect(api.login).toHaveBeenCalledTimes(1)
  })
  it('rejects an external post-login redirect', async () => {
    renderApp('/auth?next=https%3A%2F%2Fevil.example'); submitAuth()
    await screen.findByRole('heading', { name: 'Kingdom Tactics' })
    expect(screen.getByTestId('location').textContent).toBe('/')
  })
  it('clears an expired session and preserves the route', async () => {
    setAuthToken('expired')
    vi.mocked(api.me).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', 'Expired'))
    renderApp('/games/g1/play')
    await screen.findByRole('heading', { name: 'Log in' })
    await waitFor(() => expect(getAuthToken()).toBeNull())
    expect(screen.getByTestId('location')).toHaveTextContent('/auth?next=%2Fgames%2Fg1%2Fplay')
  })
  it('creates a game and enters the waiting lobby without joining', async () => {
    setAuthToken('jwt'); renderApp()
    fireEvent.click(await screen.findByRole('button', { name: 'New game' }))
    await screen.findByText('Waiting for opponent…')
    expect(api.createGame).toHaveBeenCalledTimes(1)
    expect(api.joinGame).not.toHaveBeenCalled()
  })
  it('polls the overview while waiting and goes to play when preparation starts', async () => {
    setAuthToken('jwt'); renderApp('/games/g1')
    await screen.findByText('Waiting for opponent…')
    vi.mocked(api.getGame).mockResolvedValue({ ...game, state: 'PREPARATION' })
    await screen.findByRole('heading', { name: 'Game board' }, { timeout: 2500 })
    expect(vi.mocked(api.getGame).mock.calls.length).toBeGreaterThanOrEqual(2)
  })
  it('recovers a creator opening their own invite', async () => {
    setAuthToken('jwt')
    vi.mocked(api.joinGame).mockRejectedValue(new ApiError(400, 'ALREADY_IN_GAME', 'Already joined'))
    renderApp('/join/g1')
    await screen.findByText('Waiting for opponent…')
    expect(api.joinGame).toHaveBeenCalledTimes(1)
  })
  it.each([[409, 'GAME_FULL'], [404, 'GAME_NOT_FOUND']])('shows join failure %s without retrying', async (status, code) => {
    setAuthToken('jwt')
    vi.mocked(api.joinGame).mockRejectedValue(new ApiError(Number(status), String(code), 'Cannot join this game'))
    renderApp('/join/g1')
    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot join this game')
    expect(api.joinGame).toHaveBeenCalledTimes(1)
  })
  it('returns an unauthorized join to auth with the invite preserved', async () => {
    setAuthToken('jwt')
    vi.mocked(api.joinGame).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', 'Expired'))
    renderApp('/join/g1')
    await screen.findByRole('heading', { name: 'Log in' })
    expect(screen.getByTestId('location')).toHaveTextContent('/auth?next=%2Fjoin%2Fg1')
  })
  it('sends a nonparticipant from lobby through the join route', async () => {
    setAuthToken('jwt')
    vi.mocked(api.getGame).mockRejectedValue(new ApiError(403, 'FORBIDDEN', 'Not a participant'))
    renderApp('/games/g1')
    await screen.findByRole('heading', { name: 'Game board' })
    expect(api.joinGame).toHaveBeenCalledExactlyOnceWith('g1')
  })
  it('opens finished games on the result page and creates a new game', async () => {
    setAuthToken('jwt')
    vi.mocked(api.getGame).mockResolvedValue({ ...game, state: 'FINISHED' })
    renderApp('/games/g1')
    await screen.findByRole('heading', { name: 'Victory' })
    expect(screen.getByText('Ended in round 3 of 8')).toBeInTheDocument()
    expect(screen.getByText('Opponent: 0 HP')).toBeInTheDocument()
    vi.mocked(api.createGame).mockResolvedValue({ ...game, gameId: 'g2' })
    vi.mocked(api.getGame).mockResolvedValue({ ...game, gameId: 'g2' })
    fireEvent.click(screen.getByRole('button', { name: 'New game' }))
    await screen.findByText('Waiting for opponent…')
    expect(screen.getByTestId('location').textContent).toBe('/games/g2')
  })
  it('copies the frontend invite and shows feedback', async () => {
    setAuthToken('jwt')
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    renderApp('/games/g1'); await screen.findByText('Waiting for opponent…')
    fireEvent.click(screen.getByRole('button', { name: 'Copy invite link' }))
    await screen.findByRole('button', { name: 'Copied' })
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/join/g1`)
  })
  it('selects the invite when clipboard access is denied', async () => {
    setAuthToken('jwt')
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: vi.fn().mockRejectedValue(new Error('Denied')) } })
    renderApp('/games/g1'); await screen.findByText('Waiting for opponent…')
    fireEvent.click(screen.getByRole('button', { name: 'Copy invite link' }))
    await screen.findByText('Copy the selected invite link manually.')
    const input = screen.getByLabelText('Invite link') as HTMLInputElement
    expect(input).toHaveFocus(); expect(input.selectionEnd).toBe(input.value.length)
  })
  it('logs out and removes cached identity', async () => {
    setAuthToken('jwt'); const client = renderApp('/games/g1')
    await screen.findByText('Waiting for opponent…')
    fireEvent.click(screen.getByRole('button', { name: 'Log out' }))
    await screen.findByRole('heading', { name: 'Log in' })
    expect(getAuthToken()).toBeNull(); expect(client.getQueryData(['me'])).toBeUndefined()
  })
})

describe('play page state', () => {
  it('polls GET /state and keeps polling through a round-result window', async () => {
    setAuthToken('jwt')
    vi.mocked(api.getState).mockResolvedValue({ ...state, state: 'ROUND_RESULT', latestResolvedRound: 1 })
    renderApp('/games/g1/play')
    await screen.findByText('Phase: ROUND_RESULT')
    expect(api.getGame).not.toHaveBeenCalled()
    vi.mocked(api.getState).mockResolvedValue({ ...state, currentRound: 2 })
    await screen.findByText('Round 2', {}, { timeout: 2500 })
    expect(vi.mocked(api.getState).mock.calls.length).toBeGreaterThanOrEqual(2)
  })
  it('leaves play for the result route when the match finishes', async () => {
    setAuthToken('jwt'); renderApp('/games/g1/play')
    await screen.findByText('Phase: PREPARATION')
    vi.mocked(api.getState).mockResolvedValue({ ...state, state: 'FINISHED' })
    await screen.findByRole('heading', { name: 'Match result' }, { timeout: 2500 })
    expect(screen.getByTestId('location').textContent).toBe('/games/g1/result')
  })
  it('expires an unauthorized state poll onto auth with the play route preserved', async () => {
    setAuthToken('jwt')
    vi.mocked(api.getState).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', 'Expired'))
    renderApp('/games/g1/play')
    await screen.findByRole('heading', { name: 'Log in' })
    await waitFor(() => expect(getAuthToken()).toBeNull())
    expect(screen.getByTestId('location')).toHaveTextContent('/auth?next=%2Fgames%2Fg1%2Fplay')
  })
})

describe('owned navigation paths', () => {
  it.each(['https://evil.example', '//evil.example', '/\\evil.example', '/join/%2f%2fevil', '/unknown'])('rejects %s', (path) => {
    expect(safeNext(path)).toBe('/')
  })
  it('accepts owned routes and local invites', () => {
    expect(safeNext('/join/g1')).toBe('/join/g1')
    expect(safeNext('/games/g1/play?view=mine')).toBe('/games/g1/play?view=mine')
    expect(inviteDestination('g1')).toBe('/join/g1')
    expect(inviteDestination(`${window.location.origin}/join/g1`)).toBe('/join/g1')
    expect(inviteDestination('https://evil.example/join/g1')).toBeNull()
  })
})


describe('match results', () => {
  it.each([
    ['u1', 'Victory'], ['u2', 'Defeat'], [null, 'Draw'],
  ])('shows the server outcome for winner %s after a direct result-page load', async (winnerId, heading) => {
    setAuthToken('jwt')
    vi.mocked(api.getMatchResult).mockResolvedValue({ gameId: 'g1', state: 'FINISHED', winnerId,
      winnerUsername: winnerId ? 'Winner' : null, loserUsername: winnerId ? 'Loser' : null,
      finalKeepHp: [12, 12], finalRound: 8, durationSeconds: 125, finishedAt: '' })
    renderApp('/games/g1/result')
    await screen.findByRole('heading', { name: heading! })
    expect(api.getMatchResult).toHaveBeenCalledWith('g1')
    expect(screen.getByText('Ended in round 8 of 8')).toBeInTheDocument()
    expect(screen.getByText('Duration: 2m 5s')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Game id or invite link'), { target: { value: 'g2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Join game' }))
    await waitFor(() => expect(api.joinGame).toHaveBeenCalledWith('g2'))
  })
  it('can retry a failed result request', async () => {
    setAuthToken('jwt')
    const result = await api.getMatchResult('g1')
    vi.mocked(api.getMatchResult).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'Connection lost'))
    renderApp('/games/g1/result')
    await screen.findByText('Connection lost')
    vi.mocked(api.getMatchResult).mockResolvedValue(result)
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await screen.findByRole('heading', { name: 'Victory' })
  })
  it('handles an unfinished match without displaying an outcome', async () => {
    setAuthToken('jwt')
    vi.mocked(api.getMatchResult).mockRejectedValue(new ApiError(409, 'WRONG_GAME_STATE', 'Not finished'))
    renderApp('/games/g1/result')
    await screen.findByText('This match is still in progress.')
    expect(screen.getByRole('link', { name: 'Return to game' })).toHaveAttribute('href', '/games/g1/play')
    expect(screen.queryByRole('region', { name: 'Final match outcome' })).not.toBeInTheDocument()
  })
  it('expires a rejected result request with its return path preserved', async () => {
    setAuthToken('jwt')
    vi.mocked(api.getMatchResult).mockRejectedValue(new ApiError(401, 'UNAUTHORIZED', 'Expired'))
    renderApp('/games/g1/result')
    await screen.findByRole('heading', { name: 'Log in' })
    expect(getAuthToken()).toBeNull()
    expect(screen.getByTestId('location')).toHaveTextContent('/auth?next=%2Fgames%2Fg1%2Fresult')
  })
})

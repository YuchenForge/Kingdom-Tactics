# Kingdom Tactics frontend

Phase 5 React + TypeScript UI is implemented. Automatic combat animation is Phase 6; see [roadmap and verification status](../ROADMAP.md#phase-5-frontend-mvp).

## Run

Use Node 20.19+ or 22.12+. Start the API with migrations applied, then the worker (see [local setup](../docs/local-setup.md)). From `frontend/`:

```sh
npm ci
npm run dev
```

`.env.development` uses `VITE_API_BASE_URL=/api`. Vite proxies `/api` to `http://localhost:8080`; an absolute API URL bypasses that proxy.

## Verify

```sh
npm test
npm run lint
npm run build
```

Build includes TypeScript checking; `npm run typecheck` is available separately. Full-suite baseline: 100 tests passed. After the 2026-10-02 layout polish, all 34 app tests, lint, and production build passed; the full suite was not repeated. Avoid repeating a full two-browser match for unrelated UI edits.

## Implementation

- `main.tsx`: shared QueryClient and BrowserRouter; `App.tsx`: routes.
- `hooks/useAuth.ts`: storage-backed session and profile query; invite redirects survive auth.
- `api/client.ts`, `api/gameApi.ts`, `types/index.ts`: typed API, JWT, error mapping, idempotency and round identity.
- `hooks/useGameState.ts`: continuous polling, stale-command rejection, resolved-round fetching, FINISHED navigation.
- `components/PlanningControls.tsx`, `PlanningTimer.tsx`, `UnitPopup.tsx`: placement/shop/lane, server-deadline countdown, viewport-safe details and Sell.
- `components/CombatBoard.tsx`: both locked starting formations during combat; planning returns next round. No client combat simulation.
- `pages/ResultPage.tsx`: server outcome, final HP, round/duration, errors/retry, and create/join again through `GameActions`.

Live browser review verified the existing round-six victory result, New game → waiting lobby, and Copy invite feedback. Two-player gameplay was already confirmed by the user. Full backend CI and a separately documented live eight-round completion remain pending; the subsequent visual polish was checked by app tests/build, not another browser match.

## UI layout and shared presentation

- `pages/LandingPage.tsx`: existing logo artwork and separate Create a match / Join a friend cards. `GameActions` enables these cards with its optional `split` prop; results use the compact default.
- `pages/LobbyPage.tsx`: You/Opponent cards, readiness labels, grouped invite field/copy action, and collapsible match details. Polling and invite behavior are unchanged.
- `pages/ResultPage.tsx` and `styles/result-page.css`: prominent outcome, round/duration summary, side-by-side Keep cards with player names, muted destroyed Keeps, collapsible game id, and Play again controls.
- `index.css`: shared filled cyan `.primary-action`, quieter `.text-action`, landing/lobby cards, and responsive form layouts. Result-specific styles stay scoped to `.result-page`.
- `styles/game-board.css`: larger formation, Keeps centered in adjacent space, desktop shop at the right edge, top-right round/gold/timer, single-line lane counter, and lane-side Recruit/Lock controls. Narrow screens use responsive layouts.
- `components/InterfaceIcon.tsx`: inline markup from existing `public/assets/ui/` SVGs. Keep this markup synchronized if the source artwork changes.
- `lib/abilityDescription.ts`: readable ability descriptions shared by shop cards and unit popups. This is display copy, not combat logic; healing values come from the server.
- `index.html`: Kingdom Tactics title and `/assets/brand/favicon.svg`.

`docs/art/preview.html` remains a static visual reference, not a playable app. No combat replay or animation was added by this polish.

Lock board submits immediately on the first click; pending/locked-state guards still prevent duplicate commands. The game-page logout action sits in the top bar, and desktop board height adapts to the viewport with shop overflow kept inside its panel.

Phase 6 will automatically animate each round on the merged board before the next preparation begins. Its proposed backend timing window preserves a fresh 45-second planning period. Historical replay routes and playback controls are not part of this scope; see the Phase 6 section of the roadmap.

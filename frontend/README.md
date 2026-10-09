# Frontend

React 19 and TypeScript client for Kingdom Tactics. The server owns game state and combat. This app renders that state and plays the recorded event sequence.

Setup for the full stack is in the [root README](../README.md).

## Develop

Node.js 22.12+. From `frontend/`, with the API listening on port 8080:

```sh
npm ci
npm run dev
```

`.env.development` sets `VITE_API_BASE_URL=/api`. Vite proxies that path to `http://localhost:8080`. An absolute API URL skips the proxy.

## Checks

```sh
npm test
npm run lint
npm run build
```

`npm run build` includes the TypeScript check. `npm run typecheck` runs that check on its own. `npm run test:e2e` is the two-browser match against a disposable Compose stack; see the root README.

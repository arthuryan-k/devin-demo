# web

The trading workstation: a dense, dark, multi-panel trading UI in the style of a professional broker terminal. The
same build runs against the [gateway](../gateway/README.md) or, for the
[live demo](https://arthuryan-k.github.io/devin-demo/), an in-browser fake exchange.

**Stack:** React 18, TypeScript, Vite. Tests: Vitest + Testing Library, Playwright.

## Features

- **Login / open account**, then a workstation for the four listed symbols.
- **Watchlist** with last, change, bid/ask and volume; click a row to switch symbol.
- **Per-symbol market**: quote header, price chart (line or 15-second candles with volume), L2 depth ladder with
  your own resting size marked, and time & sales.
- **Order ticket**: buy/sell, `LIMIT`/`MARKET`, `GTC`/`IOC`/`FOK`, with estimated cost and available funds/shares.
  Click a depth level to pre-fill the price.
- **Live orders**: modify (price/quantity) and cancel working orders.
- **Portfolio**: one shared cash balance across symbols, reserved cash, available funds, per-symbol positions with
  reserved shares, average cost, mark, market value, and realized/unrealized/session P&L.
- **Executions, order history and trade history** from the account's history.

## Live demo

The live demo is a static build of the same UI. It runs a small fake exchange in the browser: synthetic liquidity
and trade flow around a random walk, a seeded portfolio and history, and your orders matched against the synthetic
book. No backend is involved; nothing leaves the browser. Log in as `demo` / `demo1234`.

## Build, run, test

Node 20:

```
npm ci
npm run dev:mock                      # UI on the in-browser fake exchange at http://localhost:5173/
npm run dev                           # UI against the gateway (GATEWAY_URL, default http://localhost:8080)
npm test                              # component and integration tests (Vitest)
npm run test:e2e                      # builds the static Pages bundle and drives it in Chromium (Playwright)
npm run build:pages                   # static Pages bundle into dist-pages/
```

`npm run dev` proxies `/api` to `GATEWAY_URL` and `/ws` to `MARKET_DATA_URL` (default: the gateway).

Internals (the `Backend` interface, market-data replica, fake exchange) are covered in
[ARCHITECTURE.md](../../ARCHITECTURE.md#web-frontend-servicesweb).

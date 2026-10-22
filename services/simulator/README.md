# simulator

The exchange's only source of liquidity and price discovery: a load generator that runs a population of persona
traders per symbol, each with its **own real account**, trading over the **public gateway API** exactly like an
outside bot. The matching engine starts every book empty and never quotes; stop the simulator and the books drain.

**Stack:** Java 17, JDK `HttpClient` (REST + WebSocket), Jackson. Tests: JUnit 5 against an in-memory exchange.

## What it does

- **Personas** per symbol (ACME, GLOBX, INITD, NVTA), drawn by weight: market makers (~58%) quote around a hidden
  reference price and skew by inventory; aggressive takers (~19%) cross the spread with informed-flow bias toward the
  drift; maintenance traders (~19%) amend and cancel; whales (~4%) sweep the book and trigger volatility shocks.
- **Hidden reference price per symbol:** a random walk with drift and decaying volatility shocks.
- **Churn:** participants join (with an entry ramp) and leave at random; a leaving participant cancels every resting
  order it owns. Population per symbol stays between ~half and twice the target.
- **Real accounts:** every participant signs up with `POST /participants/register` (auth-service, via the gateway),
  is opened in the portfolio ledger with its cash grant plus `ENDOWMENT_SHARES` of its symbol (the exchange forbids
  shorting), then trades with its own `X-Api-Key` (`POST/PATCH/DELETE /api/orders`) and watches public market data
  (`/marketdata/<SYM>/ws`).

## Controls

Admin endpoint on `:8095` (CORS open, so the web UI can drive it):

```bash
curl -s localhost:8095/admin/status                      # population, reference prices, reply counts
curl -s -X POST localhost:8095/admin/start
curl -s -X POST localhost:8095/admin/stop                # every participant leaves and cancels its orders
curl -s -X POST 'localhost:8095/admin/rate?value=50'     # arrivals per second, all symbols
curl -s -X POST 'localhost:8095/admin/participants?value=6'   # target per symbol
curl -s -X POST 'localhost:8095/admin/reset?seed=7'      # stop, re-seed, start fresh
```

## Reproducibility

A seeded logical clock (`SEED`) draws the Poisson arrivals, which symbol each goes to, the reference-price paths and
the churn, so the **arrival process is reproducible from the seed**. What the exchange does with the resulting orders
is not: commands from many clients interleave over the network, so the engine's per-symbol `seq` differs run to run.
See [ARCHITECTURE.md](../../ARCHITECTURE.md#market-simulator).

## Configuration

| Variable | Default | |
| --- | --- | --- |
| `GATEWAY_URL` | `http://gateway:8080` | REST, sign-up and market data all go through the gateway |
| `PORTFOLIO_URL` | unset | opens each account with its endowment; unset = cash only |
| `SYMBOLS` | `ACME:100.00,GLOBX:50.00,INITD:25.00,NVTA:200.00` | symbol and starting reference price |
| `SEED`, `RATE`, `PARTICIPANTS` | `42`, `20`, `4` | arrivals/s over all symbols; target population per symbol |
| `ENDOWMENT_SHARES` | `500` | |
| `ADMIN_PORT`, `AUTOSTART` | `8095`, `true` | |

## Build, test

```bash
mvn -B test
docker compose up --build simulator     # from the repo root
```

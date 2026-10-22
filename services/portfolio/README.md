# portfolio

System of record for every account's money: one shared cash balance per account and one position per symbol,
persisted in Postgres (schema `portfolio`, migration [`V3__portfolio_ledger.sql`](../../db/migrations)). It settles
fills from the engine's `events.<symbol>` topics and serves balances, positions and P&L.

**Stack:** Python 3.12, FastAPI, psycopg 3, confluent-kafka. Tests: pytest against a throwaway Postgres (docker),
migrated by the same Flyway image compose uses.

## Model

- **Accounts are keyed by participant id** (the id the engine stamps on orders). An account opens on its first risk
  check from the auth-service `accounts` row, with `starting_cash` as its cash grant. All amounts are integer ticks
  (cents).
- **Shared cash, per-symbol positions.** Buying ACME and GLOBX draws on the same cash; shares are held per symbol.
- **Never negative, no shorting.** Cash, shares and both reservations are `>= 0`, and reservations never exceed what
  they lock (`reserved_cash <= cash`, `reserved_shares <= shares`). Postgres CHECK constraints enforce this, not only
  the code.
- **Reservations** are made by the [risk service](../risk) when an order is placed, and released here as the engine
  reports fills, cancels (including IOC/FOK remainders) and rejections.
- **P&L at average cost.** Buys add to cost basis, sells realize `(price - avg cost) * qty`, and unrealized P&L is
  marked at the symbol's last trade.

## Settlement

| Event | Effect |
| --- | --- |
| `OrderPlaced` | Reservation `PENDING` -> `OPEN`. |
| `TradeExecuted` | Buyer: cash -= price*qty, releasing `limit*qty` of the reservation (the refund when price < limit). Seller: shares and reserved shares -= qty, cash += price*qty. Both sides update average cost and P&L. |
| `OrderCancelled` | Releases the cancelled quantity's reservation. |
| `OrderAmended` | Confirms a pending amend; the order's reservation is re-sized to the new price/qty. |
| `OrderRejected` | Releases the whole reservation, or just the pending amend when the order is still live. |

Each Kafka message is settled in one transaction together with its offset (`portfolio.consumer_offsets`), so a
redelivered message is a no-op. Trades with no reservation (orders that bypassed risk) only move the mark.

## API

| Route | |
| --- | --- |
| `GET /v1/accounts/{participantId}` | Cash, reserved/available cash, equity, realized/unrealized/session P&L, positions, open orders. |
| `GET /v1/accounts/{participantId}/fills` | Settled fills, newest first. |
| `GET /v1/marks` | Last trade price per symbol. |
| `POST /v1/accounts` | Opens an account explicitly: `{"accountId": 1001, "cash": 100000, "positions": [{"symbol": "GLOBX", "shares": 10, "avgCost": 2500}]}`; `cash` defaults to the auth account's `starting_cash`. |
| `GET /healthz` | Liveness. |

```
curl -s localhost:8091/v1/accounts/1001
```

## Configuration

| Variable | Default | |
| --- | --- | --- |
| `DATABASE_URL` | `postgresql://postgres:postgres@localhost:5432/brokerage` | |
| `KAFKA_BOOTSTRAP_SERVERS` | unset | Runs the settlement consumer (group `portfolio-settlement`, topics `^events\..*`) when set. |
| `AUTO_OPEN_ACCOUNTS` | `true` | Open a ledger account on first check for any participant with an auth account. |
| `MARKET_COLLAR_PERCENT` | `5` | Market-buy protection above the reference price (used by risk). |

## Build, run, test

```
python3 -m pytest -q                  # needs docker (Postgres + Flyway images); or set TEST_DATABASE_URL
docker compose up --build portfolio   # from the repo root; host port 8091
```

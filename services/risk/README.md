# risk

Synchronous pre-trade risk check. The gateway calls it before publishing a command to `commands.<symbol>`; an
approved order has its cash (buys) or shares (sells) reserved in the [portfolio](../portfolio) ledger in the same
Postgres transaction that checked it, so concurrent orders from one account can never over-commit its shared cash or
a position.

**Stack:** Python 3.12, FastAPI, psycopg 3; it uses the portfolio service's `ledger` package (Docker build context
is `services/`).

## Rules

- **Buy:** reserves `limit price * qty` of the shared cash; rejects `INSUFFICIENT_FUNDS` when available cash is
  short.
- **Sell:** reserves `qty` shares of that symbol; rejects `NO_SHORTING` when available shares are short.
- **Market buy:** reserves at a protection price, the reference price (`referencePrice`, else the last trade) plus
  `MARKET_COLLAR_PERCENT` (default 5%), returned as `limitPrice`. The gateway must send the order to the engine as a
  LIMIT IOC at that price, so a fill can never cost more than was reserved. No reference: `NO_REFERENCE_PRICE`.
- **Amend:** reserves any extra the new price/qty needs until the engine confirms; one amend in flight per order.
- Each check locks the account row, then the position row (`SELECT ... FOR UPDATE`).

## API

All routes are `POST` with a JSON body, and return HTTP 200 for any decision (422 for a malformed body). Each
route is also served at `/v1/<op>` (e.g. `/v1/check`).

| Route | Extra fields | |
| --- | --- | --- |
| `/v1/orders/check` | `side` BUY/SELL, `orderType` LIMIT/MARKET, `timeInForce` GTC/IOC/FOK, `price` ticks or null, `qty`, optional `referencePrice` | New order. |
| `/v1/orders/check-amend` | `newPrice` or null, `newQty` (new remaining qty) or null | Amend. |
| `/v1/orders/check-cancel` | | Ownership check only; the reservation is released when `OrderCancelled` settles. |
| `/v1/orders/release` | `kind` `place` (default) or `amend` | Undo a check whose command never reached the engine (publish failed). |

Every body has `participantId` (the ledger key; `accountId` is accepted when sent alone), `symbol` and `orderId`.
`type`/`tif` are accepted for `orderType`/`timeInForce`. Engine rejections, cancels, IOC remainders and fills are
settled by the portfolio consumer, so don't call `release` for them.

```
curl -s localhost:8092/v1/orders/check -H 'content-type: application/json' \
  -d '{"participantId":1001,"symbol":"ACME","orderId":42,"side":"BUY","orderType":"LIMIT","timeInForce":"GTC","price":10050,"qty":10}'
{"approved":true,"orderId":42,"reservedCash":100500,"reservedShares":0,"limitPrice":10050,"availableCash":899500,"availableShares":0}
```

Rejection: `{"approved": false, "orderId": 42, "reason": "INSUFFICIENT_FUNDS", "message": "..."}`. Reasons:
`INSUFFICIENT_FUNDS`, `NO_SHORTING`, `UNKNOWN_ACCOUNT`, `UNKNOWN_SYMBOL`, `INVALID_ORDER`, `DUPLICATE_ORDER_ID`,
`NOT_OWN_OPEN_ORDER`, `AMEND_PENDING`, `NO_REFERENCE_PRICE`, `NOTHING_TO_RELEASE`. On `release`, `reservedCash` /
`reservedShares` are the amounts released. Treat 5xx or no answer as a rejection (fail closed).

## Configuration

`DATABASE_URL`, `DB_POOL_SIZE` (20), `AUTO_OPEN_ACCOUNTS`, `MARKET_COLLAR_PERCENT`; see the
[portfolio README](../portfolio/README.md#configuration).

## Build, run, test

```
python3 -m pytest -q                       # needs docker
docker compose up --build risk             # from the repo root; host port 8092
```

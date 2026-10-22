# gateway

The exchange's single public API. Every client request passes through it. It authenticates the caller, applies
rate limits, checks the order with the risk service, publishes the command to the bus, and returns the matching
engine's answer to the waiting HTTP call. Clients use decimal prices. The engine and the bus use integer ticks.

**Stack:** TypeScript on Node 20, Fastify 5, kafkajs, jose. Tests: Vitest, with an in-memory bus and fakes, plus an
optional run against a real Redpanda broker.

## Using it

Authenticate with one of:
- `Authorization: Bearer <jwt>`
- the `ob_session` cookie that signup/login set
- `X-Api-Key: <key>`

```bash
curl -s localhost:8080/api/auth/signup -H 'content-type: application/json' -d '{"username":"alice","password":"correct-horse"}'
TOKEN=...   # "token" from the response
curl -s localhost:8080/api/orders -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"symbol":"ACME","side":"BUY","type":"LIMIT","price":"100.25","qty":10,"timeInForce":"GTC"}'
```

```json
{"orderId":4417558253568001,"symbol":"ACME","seq":42,"correlationId":"6c1f…",
 "events":[{"type":"OrderPlaced","orderId":4417558253568001,"side":"BUY","price":"100.25","qty":10},
           {"type":"TradeExecuted","price":"100.20","qty":4,"makerOrderId":…,"takerOrderId":4417558253568001}]}
```

### Routes

| Route | Auth | |
| --- | --- | --- |
| `POST /api/orders` | yes | `symbol`, `side` (`BUY`/`SELL`), `type` (`LIMIT`/`MARKET`, default `LIMIT`), `price` (decimal, LIMIT only), `qty`, `timeInForce` (`GTC`/`IOC`/`FOK`, default `GTC`). JSON or form-encoded |
| `PATCH /api/orders/{id}` | yes | new `price` and/or `qty` (new remaining qty) |
| `DELETE /api/orders/{id}` | yes | cancel |
| `GET /api/symbols` | no | instruments with tick and lot size |
| `GET /api/me` | yes | the authenticated principal |
| `/auth/*`, `/api/auth/*`, `/api-keys`, `POST /participants/register` | auth-service rules | proxied to [auth-service](../auth-service/README.md): signup, login, logout, me, API keys, simulated participants |
| `/marketdata/*` (incl. WebSocket) | no | proxied to [market-data](../market-data/README.md), e.g. `ws://localhost:8080/marketdata/ACME/ws` |
| `GET /healthz` | no | |

The order routes return `200` with the engine's events for that command, prices as decimal strings and the engine
`seq`. If risk rejects the order, the reply is `200` with a single `RiskRejected` event (`reason`, `message`) and no
`seq`. A rejection by the engine is an `OrderRejected` event.

### Errors

Error bodies are `{"error": "<message>", "reason": "<CODE>"}`.

| Status | `reason` | When |
| --- | --- | --- |
| 400 | `BAD_REQUEST`, `UNKNOWN_SYMBOL` | bad input: price not on the tick grid, qty not a positive multiple of the lot size, missing fields |
| 401 | `MISSING_CREDENTIALS`, `INVALID_TOKEN`, `INVALID_API_KEY` | |
| 403 | `BLOCKED` | after 100 limit violations, until the gateway restarts |
| 404 | `UNKNOWN_ORDER` | |
| 429 | `RATE_LIMITED`, `MAX_OPEN_ORDERS` | 200 msgs/s per participant (burst 400), at most 100 open orders. Each 429 counts as a violation |
| 503 | `AUTH_UNAVAILABLE`, `RISK_UNAVAILABLE`, `BUS_UNAVAILABLE` | a dependency is down. Fail closed: nothing reaches the engine |
| 504 | `ENGINE_TIMEOUT` | published, but the engine didn't answer in time. Includes `orderId`/`correlationId`, so the order's state is unknown rather than lost |

## Configuration

| Variable | Default | |
| --- | --- | --- |
| `PORT` | `8080` | |
| `KAFKA_BROKERS` | `localhost:9092` | comma-separated |
| `AUTH_URL` | `http://localhost:8081` | auth-service. JWKS is read from `$AUTH_URL/.well-known/jwks.json` unless `JWKS_URL` is set |
| `JWT_ISSUER`, `JWT_AUDIENCE` | `devin-demo-auth`, `devin-demo` | |
| `INTERNAL_TOKEN` | unset | sent as `X-Internal-Token` to auth-service `/internal/*` |
| `JWT_SECRET` | unset | HS256 secret for local testing, used instead of JWKS |
| `RISK_URL` | `http://localhost:8092` | |
| `REFERENCE_DATA_URL` | unset | symbols from `GET /symbols`. Without it: ACME, GLOBX, INITD, NVTA at 0.01 |
| `MARKET_DATA_URL` | unset | enables the `/marketdata` proxy |
| `UPSTREAMS` | unset | extra authenticated proxies, e.g. `/api/portfolio=http://portfolio:8000`. They get `X-Participant-Id`/`X-Account-Id` |
| `SYMBOLS` | all | restrict to a comma-separated subset |
| `GATEWAY_INSTANCE` | `0` | 0–15, part of every order id. Must be unique per replica |
| `RESPONSE_TIMEOUT_MS` | `5000` | |
| `LIMIT_MSGS_PER_SEC`, `LIMIT_BURST`, `LIMIT_MAX_OPEN_ORDERS`, `LIMIT_MAX_VIOLATIONS` | `200`, `400`, `100`, `100` | |

## Build, run, test

```bash
npm ci
npm test                                   # fakes + in-memory bus
KAFKA_BROKERS=localhost:19092 npm test     # also the Redpanda integration test
npm run build && npm start
docker build -t devin-demo/gateway services/gateway
```

Internals are covered in [ARCHITECTURE.md](../../ARCHITECTURE.md#gateway).

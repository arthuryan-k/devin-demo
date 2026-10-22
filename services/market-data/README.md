# market-data

Streams each symbol's aggregated (L2) order book to browsers and bots over WebSocket. It consumes the matching
engine's `marketdata.<symbol>` topics, keeps a book replica per symbol, and serves the same feed the Java SSE
endpoint did: a snapshot on connect, then contiguous deltas, and a fresh snapshot whenever the stream breaks.

**Stack:** Python 3.12, FastAPI/uvicorn, aiokafka. Tests: pytest (in-memory bus, plus an optional run against a real
Redpanda broker).

## Endpoints

| Route | |
| --- | --- |
| `GET /marketdata/{symbol}/ws` (WebSocket) | live feed for one symbol; `?prices=ticks` for integer ticks instead of decimal strings |
| `GET /marketdata/{symbol}/snapshot` | current book as one snapshot message |
| `GET /marketdata/symbols` | symbols served, with tick size and current `seq` |
| `GET /healthz` | per-symbol `seq`, `ready`, subscriber count and upstream gap count |

The gateway proxies `/marketdata/*`, including WebSocket upgrades, so clients use `ws://<gateway>/marketdata/ACME/ws`.
An unknown symbol closes the socket with code `4404`.

## Messages

Every message carries `symbol` and `seq`. `seq` is that symbol's engine sequence. Prices are decimal strings
(`"100.25"`) unless the socket was opened with `?prices=ticks`.

```json
{"type":"snapshot","symbol":"ACME","seq":41,"reason":"subscribe",
 "bids":[["100.25",300,2]],"asks":[["100.50",100,1]],"trades":[...],"events":[...]}
{"type":"delta","symbol":"ACME","seq":42,
 "levels":[["u","B","100.25",200,1],["r","S","100.50",0,0]],
 "events":[{"type":"TradeExecuted","price":"100.50","qty":100,"takerSide":"SELL"}]}
```

- **Levels** are `[price, qty, orders]`. A delta's `levels` are `[op, side, price, qty, orders]`, where `op` is `a`
  (add), `u` (update) or `r` (remove), and side is `B` or `S`.
- **Ordering:** after snapshot `S`, deltas arrive as `S+1`, `S+2`, … with no holes.
- **Snapshot `reason`:**
  - `subscribe`: the first message on a new socket.
  - `resync`: the service recovered from an upstream gap.
  - `slow-consumer`: this client fell behind and its backlog was replaced.
  - `requested`: the client sent `{"type":"resync"}`.

  On any snapshot, clients replace their local book.
- **Client-side gaps:** a client that sees a gap can send `{"type":"resync"}` to get a fresh snapshot.
- **Isolation:** each symbol has its own socket path, book, sequence and subscriber queues. A gap or a slow client on
  one symbol never affects another.

## Configuration

| Variable | Default | |
| --- | --- | --- |
| `KAFKA_BROKERS` | unset | e.g. `redpanda:9092`. If unset, books stay empty (handy for UI work) |
| `SYMBOLS` | `ACME,GLOBX,INITD,NVTA` | optional `SYM:tick` entries, e.g. `ACME:0.01` |
| `REFERENCE_DATA_URL` | unset | if set and `SYMBOLS` is not, symbols and tick sizes come from `GET /symbols` |
| `MAX_BACKLOG` | `1024` | messages queued per client before it gets a `slow-consumer` snapshot |
| `PORT` | `8090` | |

## Run and test

```bash
python -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/pytest                                   # in-memory bus
KAFKA_BROKERS=localhost:19092 .venv/bin/pytest     # also runs the Redpanda test
KAFKA_BROKERS=localhost:19092 .venv/bin/python -m market_data
docker build -t devin-demo/market-data services/market-data
```

Internals are covered in [ARCHITECTURE.md](../../ARCHITECTURE.md#market-data-service).

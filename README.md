# devin-demo

A price-time priority limit order book in Java 17, with a market simulator that trades against it.

**[Live demo](https://arthuryan-k.github.io/devin-demo/)**

## Order book

A single-instrument, in-memory matching engine (`orderbook.MatchingEngine`, `orderbook.OrderBook`). It is
deterministic and single-threaded, uses integer prices (ticks) and quantities, and never uses floating point.

- **Commands:** `Place`, `Cancel` and `Amend` all go through `MatchingEngine.process(Command)` and produce events:
  `OrderPlaced`, `TradeExecuted`, `OrderCancelled`, `OrderAmended`, `OrderRejected`.
- **Matching:** price-time priority, with FIFO order taken from an engine-assigned `seqNum`. Trades execute at the
  resting (maker) order's price, and orders sweep across multiple price levels.
- **Order types:** `LIMIT` and `MARKET`.
- **Time in force:**
  - `GTC`: the unfilled remainder rests in the book.
  - `IOC`: the unfilled remainder is cancelled.
  - `FOK`: all-or-nothing, checked first with a dry run, so a rejected order leaves the book untouched.
- **Amend:** a quantity decrease at the same price keeps the order's queue position. A price change or quantity
  increase sends the order to the back of the queue, and it can cross the book like a new order.
- **Self-trade prevention:** cancel-taker. An incoming order that would match the same participant's resting order is
  cancelled, and the resting order is left alone.
- **Validation:** invalid input is returned as `OrderRejected` events and never thrown. This covers non-positive
  price or quantity, duplicate order IDs, and unknown order IDs.
- **Journal and replay:** `orderbook.journal.Journal` writes every command as JSON Lines. In the server it is an
  output-ring handler (`-Djournal.path=FILE`), so commands are journaled in global sequence order.
  `Journal.recover()` replays the journal to rebuild an identical book and counters, and tolerates a torn last line.

The book stores each side as a `TreeMap` of price levels with a FIFO queue per level, plus a hash index for O(1) cancels.

## Pipeline

The engine runs behind an explicit [LMAX Disruptor](https://lmax-exchange.github.io/disruptor/) pipeline
(`orderbook.pipeline.DisruptorPipeline`). Matching logic is unchanged; the pipeline only decides who feeds the
engine, in what order, and who hears about the results.

```
 HTTP order/cancel/amend ─┐                                                     ┌─▶ JournalHandler (JSON Lines)
                          ├─▶ Gateway ─tryPublishEvent─▶ input ring ─▶ engine ─▶ output ring ─┼─▶ MarketDataPublisher ─▶ GET /marketdata/stream (SSE)
 Simulator (personas) ────┘   account     CommandEvent    (1 thread,  ResultEvent ├─▶ ResponseRouter ─▶ CompletableFuture per HTTP caller
                              check       slot seq =      matching    same seq    └─▶ Simulator read model (replica book, personas)
                              → BUSY      global seqNum   only)
```

- **Input ring (sequencer):** a preallocated, power-of-two `CommandEvent` ring with multiple producers. Every API
  call (browser, external clients and simulated participants alike) is authenticated and admission-checked first
  (see [Participant API](#participant-api)), then `Gateway` runs the user's account reservation check and claims a
  slot with `tryPublishEvent()`. The slot's sequence is the command's global `seqNum`.
- **Backpressure:** a full ring never blocks a producer; the command is rejected with `OrderRejected` reason
  `BUSY` and its reservation is released.
- **Engine:** a single `EventHandler` thread that only matches; no I/O. `BlockingWaitStrategy` on both rings.
- **Output ring (fan-out):** every result goes onto a second ring read by independent handlers, each on its own
  thread with its own cursor: the journal writer, the market-data publisher, the response router (completes the
  waiting HTTP caller's `CompletableFuture`, keyed by sequence) and the simulator's read model. A slow handler
  only falls behind. Matching waits only once a handler lags by a whole output ring, and producers then see `BUSY`.

## Participant API

Every participant, including each simulated one, is an API client identified by an API key.

| Endpoint | Auth | Purpose |
|---|---|---|
| `POST /participants/register` (optional form field `name`) | none | Returns `{"participantId":1001,"label":"name-1001","apiKey":"ob_…"}` |
| `POST /api/orders` (`side`, `type`, `price`, `qty`, `timeInForce`) | `X-Api-Key` | Place an order as that participant |
| `PATCH /api/orders/{id}` (`price` and/or `qty`) | `X-Api-Key` | Amend one of your resting orders |
| `DELETE /api/orders/{id}` | `X-Api-Key` | Cancel one of your resting orders |

```
KEY=$(curl -s -d name=alice localhost:8080/participants/register | sed 's/.*"apiKey":"\([^"]*\)".*/\1/')
curl -s -H "X-Api-Key: $KEY" -d 'side=BUY&type=LIMIT&price=99.50&qty=10' localhost:8080/api/orders
```

- **Authentication:** keys live in an in-memory registry that maps each key to its participant ID. A missing or
  unknown key gets `401`, and touching another participant's order is rejected with `NOT_OWN_OPEN_ORDER`.
  The browser is `YOU` (participant 1). The page sets an `HttpOnly` session cookie holding a reserved key that can
  never be registered.
- **Admission control:** checks run in the gateway before anything is published to the input ring. Each
  participant has a token bucket of 200 messages/sec with a burst of 400 (orders, cancels and amends each cost one)
  and may have at most 100 open orders. A breach gets `429` with reason `RATE_LIMITED` or `MAX_OPEN_ORDERS`.
- **Blocking:** after 100 violations a participant gets `403 BLOCKED` on every call. `POST /api/reset` clears
  the counters.
- Responses carry the order ID, the global `seq` and the engine's events. `YOU` also gets its book and account view.

## Market data

`MarketDataPublisher` turns engine events into an aggregated (L2) feed, one message per global `seqNum`:

- `snapshot`: `{"type":"snapshot","seq":S,"bids":[[price,qty,orders],…],"asks":[…],"trades":[…],"events":[…]}`
- `delta`: `{"type":"delta","seq":N,"levels":[["a"|"u"|"r","B"|"S",price,qty,orders],…],"events":[…]}`. Trade
  prints are `TradeExecuted` entries in `events`. Prices are integer ticks.

`GET /marketdata/stream` is Server-Sent Events. Its first message is always a snapshot, taken under the same lock
that applies results, so there is no snapshot/subscribe race. After snapshot `S`, deltas arrive as `S+1`, `S+2`, …
with no holes. Any other sequence is a gap, and the client resyncs from a fresh snapshot. A slow client's backlog
is replaced by a single snapshot. The page keeps a local book replica from the snapshot plus deltas, and the
event log and trade tape filter the same stream. Account state is polled from `GET /api/account`.

## Market simulator

`orderbook.sim.Simulator` is an orchestrator of API clients. Each simulated participant runs on its own client thread,
registers through `POST /participants/register` and trades over HTTP with its own key and limits.

- **Order flow:** arrivals follow a Poisson process (exponential inter-arrival times) at an adjustable rate. Each
  arrival deals one participant a turn: a snapshot of the market (reference price, top 10 levels, its own orders),
  which its persona turns into authenticated order/cancel/amend calls. Runs are reproducible from a seed when
  participants run in-process (the static demo and tests).
- **Reference price:** a hidden fair value follows a random walk with rare jumps. Each jump starts a volatility
  shock that fades out over 12 seconds. During a shock, prices move more, market makers quote wider, and
  participants are more likely to leave.
- **Participants:** 2–8 are active at once. They join and leave over time, with more leaving after shocks. A
  participant cancels its resting orders before leaving. Each one is assigned a persona and a hidden
  `riskTolerance` between 0 and 1 when it joins.

| Persona | Share | Strategy |
|---|---|---|
| Market maker | ~58% | Posts small GTC quotes on both sides near the reference price. It re-quotes as the price drifts and refills an empty side of the book. Its spread and readiness to leave depend on its risk tolerance. It tracks the net of its own fills and shrinks its quotes on the side that would add to its inventory. |
| Aggressive taker | ~19% | Sends market and IOC orders, sometimes sweeping several levels. It follows recent drift, buying 55% of the time after the price rises and 45% after it falls. |
| Maintenance trader | ~19% | Keeps a few resting orders and cancels, resizes or reprices them. |
| Whale | ~4% | Occasionally sends a large market sweep, which triggers a volatility shock. |

**Your account (`YOU`).** You trade against the simulated participants with a cash and share balance. Every order is
checked against that balance before it reaches the engine.
- Buys reserve cash and sells reserve shares until the order fills or is cancelled.
- Cash and shares can never go negative, so short selling is not allowed.
- Fills settle at the trade price, and any unused reservation is refunded.
- Session gain/loss is tracked at average cost: realized on sells, unrealized on held shares marked at the last trade.

The simulation starts stopped; press **Simulate** to begin.

## Build and run

```
mvn test                              # engine, simulator, account, server and property tests
mvn compile exec:java                 # local server at http://localhost:8080/ (SIM_SEED=42 for a fixed seed)
```

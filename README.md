# devin-demo
Devin DEMO - October 2026

## Limit order book matching engine (Phase 1)

A single-instrument, in-memory limit order book with price-time priority matching, in Java 17 / Maven.
There's no I/O or networking, and it's single-threaded by design (none of the classes are thread-safe).

### Scope
- **Types** (`src/main/java/orderbook/`): `Order`, `Trade`, `Side`, commands `Command.Place` / `Command.Cancel`,
  and events `Event.OrderPlaced` / `OrderRejected` / `OrderCancelled` / `TradeExecuted` (Phase 2 adds
  `Command.Amend`, `Event.OrderAmended`, `OrderType`, `TimeInForce` and `participantId`; see below).
  Prices are integer ticks and quantities are integer units (`long`). Floating point is never used.
  FIFO priority comes from a monotonic `seqNum` that the engine assigns when it accepts an order, not from wall-clock time.
- **`OrderBook`**: `asks` and `bids` are each a `TreeMap<Long, Deque<Order>>` (bids use `Collections.reverseOrder()`).
  A `HashMap` order index gives O(1) cancel lookup. An empty price level is removed as soon as its queue empties.
  Queries (`bestBid`, `bestAsk`, `depth(n)`, `find`) return empty values, never `null`.
- **`MatchingEngine`**: an incoming order keeps matching against the front of the best opposite
  level while it crosses. Each trade executes at the **resting (maker) order's price**. A GTC limit order's remainder rests in the book.
- **Rejections** come back as `OrderRejected` events and never throw. They cover non-positive quantity or price,
  duplicate order IDs (including IDs of orders that already filled or were cancelled), and cancelling an unknown or
  inactive order ID.

### Phase 2 additions
**Commands.** `Command` now has three variants, and every one goes through `MatchingEngine.process`:
- `Place(order)`. An `Order` has a `participantId`, an `OrderType` (`LIMIT`/`MARKET`) and a `TimeInForce`
  (`GTC`/`IOC`/`FOK`). The old `new Order(id, side, price, qty)` constructor still builds an anonymous GTC limit order,
  and `Order.limit(...)` / `Order.market(...)` are factory shorthands.
  - **Market** orders have no price (any price you pass is ignored, and `OrderPlaced` reports 0). They sweep liquidity
    at any price and never rest. GTC and IOC behave the same for a market order, while FOK makes it all-or-nothing.
  - **IOC** orders cross like a limit order. The remainder is cancelled with `OrderCancelled(..., UNFILLED_REMAINDER)`.
  - **FOK** orders are checked first with a dry run over the book that uses the same crossing and self-trade
    stopping rules as real matching. If the order can't fill completely, the result is a single
    `OrderRejected(FOK_NOT_FILLABLE)`. There are no trades, the book isn't touched, no `seqNum` is consumed, and the
    ID isn't marked as used.
- `Cancel(orderId)`, unchanged.
- `Amend(orderId, newPrice, newQty)`. A `null` field means "unchanged", and `newQty` is the new *remaining*
  quantity. The engine emits `OrderAmended(orderId, oldPrice, newPrice, oldQty, newQty, seqNum, priorityRetained)`.
  An amend is rejected with `UNKNOWN_ORDER_ID` if the order isn't resting (never accepted, filled, or cancelled), and
  with `NON_POSITIVE_QUANTITY` / `NON_POSITIVE_PRICE` if a new value is zero or negative.

**Amend priority rule (full priority loss).** A pure quantity *decrease* at the same price changes `qtyRemaining`
in place and keeps the order's FIFO position and `seqNum`. Any *price change* or quantity *increase* is handled as an
internal cancel plus re-insert: the order keeps its ID but gets a new `seqNum` and goes to the back of its level. If
the new price crosses the book, the re-entered order matches like an incoming one. Split priority (where the original
slice keeps its place and only the added quantity goes to the back) isn't implemented. It could be added later as an
`AmendPolicy`.

**Self-trade prevention (`CANCEL_TAKER`).** If the next match would pair two orders with the same non-zero
`participantId`, the aggressive order's remainder is cancelled with `OrderCancelled(..., SELF_TRADE_PREVENTION)`, and
the resting order stays in the book untouched. Fills that happened before that point still stand. `participantId` 0
(`Order.NO_PARTICIPANT`) means anonymous, and anonymous orders are never treated as self-trades. Real exchanges also
offer other policies, such as cancel-maker, cancel-both, and decrement-and-cancel. Only cancel-taker is implemented.

**Journaling and replay (event sourcing).** `MatchingEngine(CommandLog)` appends every command to the log *before*
processing it, and that includes commands that end up rejected. If the append throws, the command isn't processed.
`orderbook.journal.Journal` is the file-backed `CommandLog`. It writes JSON Lines (one command per line, encoded by
`CommandCodec` with no extra dependencies) and flushes after each append. `Journal.replay()` returns the journaled
commands in order, and `Journal.recover()` (which is `MatchingEngine.replay(commands, journal)`) feeds them through
`process()` on a fresh engine and then reattaches the journal. Matching is deterministic, so replay rebuilds the
same book, the same set of used IDs, and the same counters: `nextSeqNum()` comes out identical, and `nextOrderId()`
is derived as the largest order ID seen in any `Place` plus one. A final line without a trailing newline counts as a
torn write. Replay ignores it, and the next append truncates it. A corrupt line anywhere else makes replay fail. The
core `orderbook` package still does no I/O; the file handling lives in `orderbook.journal`.
```
{"cmd":"place","id":1,"participant":7,"side":"BUY","type":"LIMIT","tif":"GTC","price":100,"qty":10}
{"cmd":"amend","id":1,"price":null,"qty":4}
{"cmd":"cancel","id":1}
```

### Known gaps / future work
- **Snapshots:** recovery replays the whole journal. A future optimization is to write periodic book snapshots
  (including the used-ID set and counters) and replay only the journal tail after the latest snapshot.
- Journal appends are flushed but not fsynced, so they survive a process crash but not necessarily a power loss.
- No stop orders. Only one self-trade policy and one amend policy exist.
- There's one instrument per engine.
- The set of accepted order IDs (used to reject duplicates) grows without bound.

### Visual demo (browser UI) with market simulation
```
mvn compile exec:java          # or: mvn compile exec:java -Dexec.args=9090
SIM_SEED=42 mvn compile exec:java   # reproducible simulation (or -Dsim.seed=42); the seed is logged at startup
```
Then open http://localhost:8080/ and press **Simulate**. None of this touches the engine core: `MatchingEngine`,
`OrderBook`, the types and the journal are unchanged, and the simulator and the account only build, check and submit
`Command`s through `MatchingEngine.process()`.

**`orderbook.sim.Simulator`** owns the `MatchingEngine` and a single-threaded executor. Every command, simulated or
from HTTP, runs on that one thread (`submit`, `execute`), so the engine stays single-threaded. `start()`, `stop()` and
`setRate(ordersPerSecond)` control a Poisson arrival loop (exponential inter-arrival delays) driven by one seedable
`Random`. Each arrival:
- advances a hidden reference price: a small random walk plus rare jumps (about one per 2 simulated minutes). A jump
  starts a 12 s volatility shock that fades linearly. During it volatility is higher, market makers quote wider, and
  participants are more likely to leave;
- applies churn: 2–8 participants, low baseline exit rate that spikes after shocks, and an exit cancels the
  participant's resting orders first. New entrants ramp up their activity;
- lets one participant act, chosen by activity weight. Each participant gets a persona and a hidden `riskTolerance`
  in [0, 1] when it spawns:

| Persona | Spawn weight | Behaviour |
|---|---|---|
| `MarketMaker` (`MM-n`) | ~58% | Small GTC quotes near the reference (peaked distance and size), re-quotes (amends) stale quotes when the reference drifts, re-seeds an empty side. Lower risk tolerance = wider spread, more widening in shocks, quicker exits. Keeps a signed counter of its own fills and shrinks quotes on the side that would add to that inventory. |
| `AggressiveTaker` (`TAKER-n`) | ~19% | Market and IOC orders, occasional multi-level sweeps; buys with probability 55% when the reference recently rose (45% when it fell). |
| `MaintenanceTrader` (`MAINT-n`) | ~19% | Keeps a few GTC orders and cancels, resizes or reprices them. |
| `Whale` (`WHALE-n`) | ~4% | Rare large market sweeps that trigger a volatility shock. |

`stop()` cancels every simulated order and removes the participants. Simulated participants never use the user's ID
and are not limited by the account system.

**`orderbook.demo.Account`** applies only to you (`"YOU"`) and is checked before your commands reach the engine.
It tracks cash, reserved cash, shares owned and reserved shares; neither cash nor shares can go negative (no short
selling, no credit). Starting cash is random but at least twice the starting reference price; you start with 0 shares.
- Limit buy: reserves `price × qty`; rejected if available cash is short. A fill pays the trade price and releases the
  reservation at the limit price, so a better fill refunds the difference. Cancel or expiry releases the rest.
- Market buy: reserves a conservative estimate: the larger of the cost of sweeping the current asks and
  `best ask (else last trade) × qty × 1.05`. Each fill releases exactly what it cost; the rest is released when the
  remainder is cancelled.
- Sell (limit or market): rejected if `qty > sharesOwned - reservedShares`; reserves the shares until fill or cancel.
- Amend: re-reserves for the new price/qty (a decrease releases, an increase needs more and can be rejected).
- Mark-to-market equity = cash + shares × last trade price.

Rejections (insufficient funds or shares, or touching someone else's order) are shown as ticket errors and as
`AccountRejected` entries in the event log.

The page has Simulate/Stop and a 1–20 orders/sec rate slider, an order ticket (BUY/SELL, LIMIT/MARKET,
GTC/IOC/FOK; no price for MARKET), cancel/amend by ID, and a balance sheet. The depth chart shows one bar per price
level split into one segment per resting order (FIFO), plus cumulative depth, spread and last trade price. The
resting-orders table, trade tape and event log show who owns each order, and your orders and trades are
highlighted. The page polls about every 500 ms.

JSON API (prices and cash are decimals with tick size 0.01; integer ticks inside the engine):
- `GET /api/book[?since=seq]`: book depth with per-order participant, trades, account, simulation status and
  participants, and event-feed entries newer than `since` (all engine event types plus `AccountRejected`).
- `POST /api/orders`: form fields `side`, `qty`, optional `type` (`LIMIT`|`MARKET`, default `LIMIT`), `price`
  (LIMIT only), `timeInForce` (`GTC`|`IOC`|`FOK`, default `GTC`), `participantId` (only `YOU`), `id`.
- `PATCH /api/orders/{id}` (`price` and/or `qty`), `DELETE /api/orders/{id}`: your orders only.
- `POST /simulate/start`, `POST /simulate/stop`, `POST /simulate/rate?perSec=N`, `POST /api/reset`.

### Build and test
```
mvn test
```
The unit tests (JUnit 5) cover resting, full and partial fills, FIFO order within a level, multi-level sweeps,
cancels, and validation. Phase 2 adds tests for the amend priority rules, market sweeps, IOC
remainder cancels, FOK all-or-nothing (including the case where self-trade prevention blocks the fill), self-trade
cancel-taker, and journal round-trip / torn-write handling. The jqwik property tests run random command sequences
(all order types and TIFs, several participants, cancels, and amends) and check these things after every command:
- Quantity is conserved: for each order, `resting + filled + cancelled = submitted + amend deltas`.
- The book is never left crossed. Only GTC limit orders rest. An accepted FOK fills completely. A rejected command
  never changes the book. No trade pairs two orders from the same participant.
- Journal round-trip: write the commands to a journal, replay them into a fresh engine, and get the same commands,
  events, book, and counters.

Simulator tests cover start/stop (stop cancels every simulated order), rate validation and exponential inter-arrivals,
exits cancelling the leaving participant's orders, the 2–8 participant bounds, shocks raising exit probability and
widening quotes, re-quoting after drift, re-seeding an empty side, the direction of inventory skew, the 55/45 taker
bias, same-seed reproducibility, and that simulated flow only sends legal commands for its own orders and never acts
as `YOU`. Account tests cover starting cash, reserving and releasing on fill/cancel, refunds when a buy fills below
its limit, insufficient funds and shares, amends, market-order reservations, engine-rejection rollback, and a jqwik
property that cash and shares never go negative. `DemoServerTest` covers the HTTP API end to end.

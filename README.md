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
- The browser demo below hasn't been updated for these features yet: it only places GTC limit orders and cancels.

### Visual demo (browser UI)
```
mvn compile exec:java          # or: mvn compile exec:java -Dexec.args=9090
```
Then open http://localhost:8080/. The `orderbook.demo.DemoServer` wraps one `MatchingEngine` behind the JDK's
built-in `HttpServer`. It handles requests one at a time on a single thread, so the engine stays single-threaded, and
the core `orderbook` package still does no I/O. The page lets you place limit orders and cancel them (from the table,
by ID, or by clicking an order in the chart). It shows the book as one bar per price level, with each bar split into
one segment per resting order in FIFO order, plus cumulative depth, the spread, the last trade price, a trade tape,
and an event log. "Seed book" and "Random order" generate test flow.

JSON API: `GET /api/book`, `POST /api/orders` (form fields `side`, `price`, `qty`, optional `id`),
`DELETE /api/orders/{id}`, `POST /api/reset`.

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

# devin-demo
Devin DEMO - October 2026

## Limit order book matching engine (Phase 1)

A single-instrument, in-memory limit order book with price-time priority matching, in Java 17 / Maven.
There's no I/O or networking, and it's single-threaded by design (none of the classes are thread-safe).

### Scope
- **Types** (`src/main/java/orderbook/`): `Order`, `Trade`, `Side`, commands `Command.Place` / `Command.Cancel`,
  and events `Event.OrderPlaced` / `OrderRejected` / `OrderCancelled` / `TradeExecuted`.
  Prices are integer ticks and quantities are integer units (`long`). Floating point is never used.
  FIFO priority comes from a monotonic `seqNum` that the engine assigns when it accepts an order, not from wall-clock time.
- **`OrderBook`**: `asks` and `bids` are each a `TreeMap<Long, Deque<Order>>` (bids use `Collections.reverseOrder()`).
  A `HashMap` order index gives O(1) cancel lookup. An empty price level is removed as soon as its queue empties.
  Queries (`bestBid`, `bestAsk`, `depth(n)`, `find`) return empty values, never `null`.
- **`MatchingEngine`**: limit orders only. An incoming order keeps matching against the front of the best opposite
  level while it crosses. Each trade executes at the **resting (maker) order's price**. Any remainder rests in the book.
- **Rejections** come back as `OrderRejected` events and never throw. They cover non-positive quantity or price,
  duplicate order IDs (including IDs of orders that already filled or were cancelled), and cancelling an unknown or
  inactive order ID.

### Known gaps
- **Self-trade prevention:** there's no participant or account tracking, so an order can match against another order
  from the same owner.
- No market, IOC, FOK, or stop orders, and no order amend/replace.
- There's one instrument per engine, and no persistence or event replay.
- The set of accepted order IDs (used to reject duplicates) grows without bound.

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
cancels, and validation. The jqwik property tests run random command sequences and check two things after every command:
- Quantity is conserved: for each order, `resting + filled + cancelled = submitted`.
- The book is never left crossed.

package orderbook.demo;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import orderbook.Command;
import orderbook.Event;
import orderbook.Order;
import orderbook.OrderBook;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.Trade;
import orderbook.sim.Simulator;

/**
 * Local HTTP demo for poking at the matching engine from a browser, with a market simulation running against it.
 * Not part of the engine itself.
 *
 * <p>Runs on embedded Jetty: REST endpoints for commands and polling, plus a WebSocket at {@code /ws} that pushes
 * sequenced deltas (see {@link BookStream}).
 *
 * <p>Threading: the engine lives inside the {@link Simulator}, and everything that touches it (simulated flow, user
 * commands, building the JSON state and stream frames) runs on the simulator's single thread. Jetty request threads
 * hop onto it via {@link Simulator#execute}.
 *
 * <p>Orders from the HTTP API always belong to the user ({@code "YOU"}) and pass through the {@link Account} checks
 * before reaching the engine. Prices and cash are decimals in the API (tick = 0.01) and integer ticks inside.
 */
public final class DemoServer {

    private static final int MAX_TRADES = 100;
    private static final int MAX_FEED = 500;
    private static final int MAX_FEED_PER_RESPONSE = 200;
    private static final int DEPTH_LEVELS = 30;
    static final long USER = Simulator.USER_PARTICIPANT_ID;
    private static final long STREAM_FLUSH_MILLIS = 50;
    private static final Duration WS_IDLE_TIMEOUT = Duration.ofSeconds(60);

    private record FeedEntry(long seq, String json) {
    }

    private final Server server;
    private final ServerConnector connector;
    private final Simulator simulator;
    private final BookStream stream;
    private final Supplier<Account> accountFactory;

    // Confined to the simulator thread.
    private Account account;
    private final ArrayDeque<String> trades = new ArrayDeque<>();
    private long tradeCount;
    private long tradedVolume;
    private final ArrayDeque<FeedEntry> feed = new ArrayDeque<>();
    private long feedSeq;

    public DemoServer(InetSocketAddress address) throws IOException {
        this(address, new Simulator(defaultSeed()), null);
    }

    /** {@code accountFactory} builds the user's account at startup and on reset; null for a random starting cash. */
    public DemoServer(InetSocketAddress address, Simulator simulator, Supplier<Account> accountFactory)
            throws IOException {
        this.simulator = simulator;
        Random accountRandom = new Random(simulator.seed() ^ 0x5DEECE66DL);
        this.accountFactory = accountFactory != null ? accountFactory
                : () -> Account.withRandomCash(USER, accountRandom, simulator.startingReferenceTicks());
        simulator.run(() -> account = this.accountFactory.get());
        stream = new BookStream(simulator, new StreamSource(), STREAM_FLUSH_MILLIS);
        simulator.addListener(this::onCommand);

        server = new Server();
        connector = new ServerConnector(server);
        connector.setHost(address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString());
        connector.setPort(address.getPort());
        server.addConnector(connector);
        WebSocketUpgradeHandler ws = WebSocketUpgradeHandler.from(server, container -> {
            container.setIdleTimeout(WS_IDLE_TIMEOUT);
            container.addMapping("/ws", (req, res, cb) -> new StreamSocket(sinceParam(req.getHttpURI().getQuery())));
        });
        ws.setHandler(new Routes());
        server.setHandler(ws);
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        DemoServer demo = new DemoServer(new InetSocketAddress(port));
        demo.start();
        System.out.println("Order book demo running at http://localhost:" + demo.port() + "/ (simulator seed "
                + demo.simulator.seed() + ")");
    }

    /** {@code -Dsim.seed=N} or {@code SIM_SEED=N} for a reproducible simulation; random otherwise. */
    private static long defaultSeed() {
        String text = System.getProperty("sim.seed", System.getenv("SIM_SEED"));
        return text != null && !text.isBlank() ? Long.parseLong(text.trim()) : new SecureRandom().nextLong();
    }

    public void start() throws IOException {
        try {
            server.start();
        } catch (Exception e) {
            throw new IOException("failed to start server", e);
        }
        stream.start();
    }

    /** Closes stream clients, stops the HTTP server and shuts down the simulator. */
    public void stop() {
        stream.close();
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("failed to stop server", e);
        } finally {
            simulator.close();
        }
    }

    public int port() {
        return connector.getLocalPort();
    }

    Simulator simulator() {
        return simulator;
    }

    private record Reply(int status, String json) {
        static Reply ok(String json) {
            return new Reply(200, json);
        }

        static Reply error(int status, String message) {
            return new Reply(status, "{\"error\":" + Json.str(message) + "}");
        }
    }

    /** One HTTP request; {@code query} is still URL-encoded. */
    private record Call(String method, String path, String query, Request request) {
        String body() throws IOException {
            return Content.Source.asString(request, StandardCharsets.UTF_8);
        }
    }

    @FunctionalInterface
    private interface Endpoint {
        Reply handle(Call call) throws IOException;
    }

    private Endpoint route(String path) {
        if (under(path, "/api/book")) {
            return this::book;
        }
        if (under(path, "/api/orders")) {
            return this::orders;
        }
        if (under(path, "/api/reset")) {
            return this::reset;
        }
        if (under(path, "/simulate")) {
            return this::simulate;
        }
        return null;
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private final class Routes extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            Call call = new Call(request.getMethod(), request.getHttpURI().getPath(), request.getHttpURI().getQuery(),
                    request);
            Endpoint endpoint = route(call.path());
            if (endpoint == null) {
                serveStatic(call, response, callback);
                return true;
            }
            Reply reply;
            try {
                reply = endpoint.handle(call);
            } catch (IllegalArgumentException e) {
                reply = Reply.error(400, e.getMessage());
            } catch (RuntimeException e) {
                reply = Reply.error(500, String.valueOf(e.getMessage()));
            }
            send(response, callback, reply.status(), "application/json", reply.json().getBytes(StandardCharsets.UTF_8));
            return true;
        }
    }

    // ---- WebSocket stream -----------------------------------------------------------------------------------------

    private static OptionalLong sinceParam(String query) {
        String text = parseForm(query).get("since");
        if (text == null || text.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(text.trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** One browser connection; inbound text (keep-alive pings) is ignored. Public because Jetty invokes it reflectively. */
    public final class StreamSocket implements Session.Listener.AutoDemanding {
        private final OptionalLong since;
        private volatile Outbox outbox;

        StreamSocket(OptionalLong since) {
            this.since = since;
        }

        @Override
        public void onWebSocketOpen(Session session) {
            outbox = stream.connect(new Outbox.Transport() {
                @Override
                public void send(String text, Runnable onSuccess, Consumer<Throwable> onFailure) {
                    session.sendText(text, org.eclipse.jetty.websocket.api.Callback.from(onSuccess, onFailure));
                }

                @Override
                public void close() {
                    session.close();
                }
            }, since);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason,
                org.eclipse.jetty.websocket.api.Callback callback) {
            disconnect();
            callback.succeed();
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            disconnect();
        }

        private void disconnect() {
            Outbox out = outbox;
            if (out != null) {
                stream.disconnect(out);
            }
        }
    }

    /** Stream views of the server state; simulator thread only. */
    private final class StreamSource implements BookStream.Source {
        @Override
        public String order(long orderId) {
            return simulator.engine().book().find(orderId).map(DemoServer.this::orderJson).orElse(null);
        }

        @Override
        public List<String> restingOrders() {
            OrderBook book = simulator.engine().book();
            List<String> out = new ArrayList<>();
            for (Side side : Side.values()) {
                for (Order o : book.orders(side)) {
                    out.add(orderJson(o));
                }
            }
            return out;
        }

        @Override
        public String trades() {
            return Json.array(new ArrayList<>(trades));
        }

        @Override
        public String feedSince(long since) {
            return DemoServer.this.feedSince(since);
        }

        @Override
        public long feedSeq() {
            return feedSeq;
        }

        @Override
        public String stats() {
            return statsJson();
        }

        @Override
        public String account() {
            return accountJson();
        }

        @Override
        public String simulation() {
            return simulationJson();
        }
    }

    // ---- handlers -------------------------------------------------------------------------------------------------

    private Reply book(Call call) {
        if (!call.method().equals("GET")) {
            return Reply.error(405, "use GET");
        }
        String sinceText = parseForm(call.query()).get("since");
        long since = sinceText == null || sinceText.isBlank() ? 0 : parseLong("since", sinceText);
        return Reply.ok(simulator.execute(() -> stateJson(since)));
    }

    private Reply orders(Call call) throws IOException {
        String method = call.method();
        String path = call.path();
        if (method.equals("POST") && path.equals("/api/orders")) {
            return place(parseForm(call.body()));
        }
        if (path.startsWith("/api/orders/")) {
            long id = parseLong("id", path.substring("/api/orders/".length()));
            if (method.equals("DELETE")) {
                return simulator.execute(() -> submitUser(new Command.Cancel(id)));
            }
            if (method.equals("PATCH")) {
                return amend(id, parseForm(call.body()));
            }
        }
        return Reply.error(405, "use POST /api/orders, PATCH /api/orders/{id} or DELETE /api/orders/{id}");
    }

    private Reply place(Map<String, String> form) {
        String participant = form.getOrDefault("participantId", Simulator.USER_LABEL).trim();
        if (!participant.isEmpty() && !participant.equals(Simulator.USER_LABEL)) {
            throw new IllegalArgumentException("participantId must be " + Simulator.USER_LABEL);
        }
        Side side = parseEnum(Side.class, "side", form.get("side"), null);
        OrderType type = parseEnum(OrderType.class, "type", form.get("type"), OrderType.LIMIT);
        TimeInForce tif = parseEnum(TimeInForce.class, "timeInForce", form.get("timeInForce"), TimeInForce.GTC);
        long qty = parseLong("qty", form.get("qty"));
        long price = type == OrderType.LIMIT ? Ticks.parse("price", form.get("price")) : 0;
        String idText = form.getOrDefault("id", "").trim();
        Long requestedId = idText.isEmpty() ? null : parseLong("id", idText);

        return simulator.execute(() -> {
            long id = requestedId != null ? requestedId : simulator.engine().nextOrderId();
            Order order = type == OrderType.LIMIT ? Order.limit(id, USER, side, price, qty, tif)
                    : Order.market(id, USER, side, qty, tif);
            return submitUser(new Command.Place(order));
        });
    }

    private Reply amend(long id, Map<String, String> form) {
        String priceText = form.getOrDefault("price", "").trim();
        String qtyText = form.getOrDefault("qty", "").trim();
        Long price = priceText.isEmpty() ? null : Ticks.parse("price", priceText);
        Long qty = qtyText.isEmpty() ? null : parseLong("qty", qtyText);
        if (price == null && qty == null) {
            throw new IllegalArgumentException("price or qty is required");
        }
        return simulator.execute(() -> submitUser(new Command.Amend(id, price, qty)));
    }

    /** Runs on the simulator thread: account check, then the engine. */
    private Reply submitUser(Command command) {
        Optional<Account.Rejection> rejection = account.admit(command, simulator.engine().book());
        if (rejection.isPresent()) {
            Account.Rejection r = rejection.get();
            String json = Json.obj("type", Json.str("AccountRejected"), "orderId", Long.toString(r.orderId()),
                    "participant", Json.str(Simulator.USER_LABEL), "reason", Json.str(r.reason().name()),
                    "message", Json.str(r.message()));
            addFeed(json);
            return Reply.ok("{\"events\":[" + json + "],\"book\":" + stateJson(Long.MAX_VALUE) + "}");
        }
        List<Event> events = simulator.submit(USER, command);
        List<String> eventJson = new ArrayList<>();
        for (Event event : events) {
            eventJson.add(eventJson(event, USER));
        }
        return Reply.ok("{\"events\":" + Json.array(eventJson) + ",\"book\":" + stateJson(Long.MAX_VALUE) + "}");
    }

    private Reply reset(Call call) {
        if (!call.method().equals("POST")) {
            return Reply.error(405, "use POST");
        }
        simulator.reset();
        return Reply.ok(simulator.execute(() -> {
            account = accountFactory.get();
            trades.clear();
            tradeCount = 0;
            tradedVolume = 0;
            feed.clear();
            stream.onReset();
            return stateJson(Long.MAX_VALUE);
        }));
    }

    private Reply simulate(Call call) throws IOException {
        if (!call.method().equals("POST")) {
            return Reply.error(405, "use POST");
        }
        switch (call.path()) {
            case "/simulate/start" -> simulator.start();
            case "/simulate/stop" -> simulator.stop();
            case "/simulate/rate" -> {
                Map<String, String> params = parseForm(call.query());
                params.putAll(parseForm(call.body()));
                String text = params.get("perSec");
                if (text == null || text.isBlank()) {
                    throw new IllegalArgumentException("perSec is required");
                }
                double perSec;
                try {
                    perSec = Double.parseDouble(text.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("perSec must be a number");
                }
                simulator.setRate(perSec);
            }
            default -> {
                return Reply.error(404, "use /simulate/start, /simulate/stop or /simulate/rate?perSec=N");
            }
        }
        return Reply.ok(simulator.execute(() -> "{\"simulation\":" + simulationJson() + ",\"book\":"
                + stateJson(Long.MAX_VALUE) + "}"));
    }

    // ---- event feed -----------------------------------------------------------------------------------------------

    /** Simulator listener: settles the account and records trades and events for every command. */
    private void onCommand(long participantId, Command command, List<Event> events) {
        account.onEvents(events);
        for (Event event : events) {
            if (event instanceof Event.TradeExecuted executed) {
                Trade t = executed.trade();
                tradeCount++;
                tradedVolume += t.qty();
                String json = "{\"n\":" + tradeCount + "," + tradeJson(t).substring(1);
                trades.addFirst(json);
                stream.onTrade(json);
                stream.markOrder(t.makerOrderId());
                stream.markOrder(t.takerOrderId());
            } else if (event instanceof Event.OrderPlaced e) {
                stream.markOrder(e.orderId());
            } else if (event instanceof Event.OrderCancelled e) {
                stream.markOrder(e.orderId());
            } else if (event instanceof Event.OrderAmended e) {
                stream.markOrder(e.orderId());
            }
            addFeed(eventJson(event, participantId));
        }
        while (trades.size() > MAX_TRADES) {
            trades.removeLast();
        }
    }

    private void addFeed(String json) {
        long seq = ++feedSeq;
        feed.addLast(new FeedEntry(seq, "{\"seq\":" + seq + "," + json.substring(1)));
        while (feed.size() > MAX_FEED) {
            feed.removeFirst();
        }
    }

    private String feedSince(long since) {
        List<String> out = new ArrayList<>();
        for (FeedEntry entry : feed) {
            if (entry.seq() > since) {
                out.add(entry.json());
            }
        }
        if (out.size() > MAX_FEED_PER_RESPONSE) {
            out = out.subList(out.size() - MAX_FEED_PER_RESPONSE, out.size());
        }
        return Json.array(out);
    }

    // ---- JSON -----------------------------------------------------------------------------------------------------

    /** Full state; {@code events} holds feed entries with {@code seq > since}. Simulator thread only. */
    private String stateJson(long since) {
        OrderBook book = simulator.engine().book();
        OptionalLong bid = book.bestBid();
        OptionalLong ask = book.bestAsk();
        return Json.obj(
                "tickSize", "0.01",
                "bestBid", price(bid),
                "bestAsk", price(ask),
                "spread", bid.isPresent() && ask.isPresent() ? Ticks.format(ask.getAsLong() - bid.getAsLong()) : "null",
                "lastTradePrice", price(account.lastTradePrice()),
                "bids", levelsJson(book.orders(Side.BUY)),
                "asks", levelsJson(book.orders(Side.SELL)),
                "restingOrders", Integer.toString(book.size()),
                "trades", Json.array(new ArrayList<>(trades)),
                "tradeCount", Long.toString(tradeCount),
                "tradedVolume", Long.toString(tradedVolume),
                "nextOrderId", Long.toString(simulator.engine().nextOrderId()),
                "account", accountJson(),
                "simulation", simulationJson(),
                "events", feedSince(since),
                "eventSeq", Long.toString(feedSeq));
    }

    /** Top-of-book and tape counters, as sent in every stream frame. */
    private String statsJson() {
        OrderBook book = simulator.engine().book();
        OptionalLong bid = book.bestBid();
        OptionalLong ask = book.bestAsk();
        return Json.obj(
                "tickSize", "0.01",
                "bestBid", price(bid),
                "bestAsk", price(ask),
                "spread", bid.isPresent() && ask.isPresent() ? Ticks.format(ask.getAsLong() - bid.getAsLong()) : "null",
                "lastTradePrice", price(account.lastTradePrice()),
                "restingOrders", Integer.toString(book.size()),
                "tradeCount", Long.toString(tradeCount),
                "tradedVolume", Long.toString(tradedVolume));
    }

    private String orderJson(Order o) {
        return Json.obj("id", Long.toString(o.id()), "side", Json.str(o.side().name()), "price", Ticks.format(o.price()),
                "qty", Long.toString(o.qtyRemaining()), "seqNum", Long.toString(o.seqNum()),
                "participant", Json.str(simulator.label(o.participantId())),
                "mine", Boolean.toString(o.participantId() == USER));
    }

    private String accountJson() {
        List<String> orders = new ArrayList<>();
        for (Account.OpenOrder o : account.openOrders()) {
            orders.add(Json.obj("id", Long.toString(o.id()), "side", Json.str(o.side().name()),
                    "type", Json.str(o.type().name()), "timeInForce", Json.str(o.timeInForce().name()),
                    "price", o.type() == OrderType.MARKET ? "null" : Ticks.format(o.price()),
                    "qty", Long.toString(o.qty()), "reservedCash", Ticks.format(o.reservedCash())));
        }
        return Json.obj(
                "participant", Json.str(Simulator.USER_LABEL),
                "cash", Ticks.format(account.cash()),
                "reservedCash", Ticks.format(account.reservedCash()),
                "availableCash", Ticks.format(account.availableCash()),
                "sharesOwned", Long.toString(account.sharesOwned()),
                "reservedShares", Long.toString(account.reservedShares()),
                "availableShares", Long.toString(account.availableShares()),
                "markPrice", price(account.lastTradePrice()),
                "equity", Ticks.format(account.equity()),
                "openOrders", Json.array(orders));
    }

    private String simulationJson() {
        List<String> participants = new ArrayList<>();
        for (Simulator.ParticipantInfo p : simulator.participants()) {
            participants.add(Json.obj("id", Long.toString(p.id()), "label", Json.str(p.label()),
                    "kind", Json.str(p.kind().name())));
        }
        return Json.obj("running", Boolean.toString(simulator.isRunning()),
                "ratePerSec", Double.toString(simulator.rate()),
                "seed", Json.str(Long.toString(simulator.seed())),
                "participants", Json.array(participants));
    }

    /** Groups orders (already in priority order) into the best {@value #DEPTH_LEVELS} levels, FIFO within each. */
    private String levelsJson(List<Order> orders) {
        List<String> levels = new ArrayList<>();
        int i = 0;
        while (i < orders.size() && levels.size() < DEPTH_LEVELS) {
            long price = orders.get(i).price();
            long total = 0;
            List<String> orderJson = new ArrayList<>();
            while (i < orders.size() && orders.get(i).price() == price) {
                Order o = orders.get(i++);
                total += o.qtyRemaining();
                orderJson.add(Json.obj("id", Long.toString(o.id()), "qty", Long.toString(o.qtyRemaining()),
                        "seqNum", Long.toString(o.seqNum()), "participant", Json.str(simulator.label(o.participantId())),
                        "mine", Boolean.toString(o.participantId() == USER)));
            }
            levels.add(Json.obj("price", Ticks.format(price), "totalQty", Long.toString(total),
                    "orders", Json.array(orderJson)));
        }
        return Json.array(levels);
    }

    private String owner(long orderId, long fallbackParticipant) {
        long owner = simulator.participantOf(orderId);
        return Json.str(simulator.label(owner != Order.NO_PARTICIPANT ? owner : fallbackParticipant));
    }

    private String tradeJson(Trade t) {
        return Json.obj("makerOrderId", Long.toString(t.makerOrderId()), "takerOrderId", Long.toString(t.takerOrderId()),
                "makerParticipant", owner(t.makerOrderId(), Order.NO_PARTICIPANT),
                "takerParticipant", owner(t.takerOrderId(), Order.NO_PARTICIPANT),
                "takerSide", takerSide(t),
                "price", Ticks.format(t.price()), "qty", Long.toString(t.qty()));
    }

    private String takerSide(Trade t) {
        Side taker = simulator.sideOf(t.takerOrderId());
        return taker == null ? "null" : Json.str(taker.name());
    }

    private String eventJson(Event event, long issuer) {
        if (event instanceof Event.OrderPlaced e) {
            return Json.obj("type", Json.str("OrderPlaced"), "orderId", Long.toString(e.orderId()),
                    "participant", owner(e.orderId(), issuer), "side", Json.str(e.side().name()),
                    "price", e.price() == 0 ? "null" : Ticks.format(e.price()), "qty", Long.toString(e.qty()));
        }
        if (event instanceof Event.OrderRejected e) {
            return Json.obj("type", Json.str("OrderRejected"), "orderId", Long.toString(e.orderId()),
                    "participant", Json.str(simulator.label(issuer)), "reason", Json.str(e.reason().name()));
        }
        if (event instanceof Event.OrderCancelled e) {
            return Json.obj("type", Json.str("OrderCancelled"), "orderId", Long.toString(e.orderId()),
                    "participant", owner(e.orderId(), issuer), "cancelledQty", Long.toString(e.cancelledQty()),
                    "reason", Json.str(e.reason().name()));
        }
        if (event instanceof Event.OrderAmended e) {
            return Json.obj("type", Json.str("OrderAmended"), "orderId", Long.toString(e.orderId()),
                    "participant", owner(e.orderId(), issuer),
                    "oldPrice", Ticks.format(e.oldPrice()), "newPrice", Ticks.format(e.newPrice()),
                    "oldQty", Long.toString(e.oldQty()), "newQty", Long.toString(e.newQty()),
                    "priorityRetained", Boolean.toString(e.priorityRetained()));
        }
        if (event instanceof Event.TradeExecuted e) {
            return Json.obj("type", Json.str("TradeExecuted"), "trade", tradeJson(e.trade()));
        }
        throw new IllegalStateException("unknown event " + event);
    }

    private static String price(OptionalLong ticks) {
        return ticks.isPresent() ? Ticks.format(ticks.getAsLong()) : "null";
    }

    // ---- HTTP helpers ---------------------------------------------------------------------------------------------

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String name, String value, E defaultValue) {
        if (value == null || value.isBlank()) {
            if (defaultValue != null) {
                return defaultValue;
            }
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            List<String> names = new ArrayList<>();
            for (E constant : type.getEnumConstants()) {
                names.add(constant.name());
            }
            throw new IllegalArgumentException(name + " must be one of " + String.join(", ", names));
        }
    }

    private void serveStatic(Call call, Response response, Callback callback) throws IOException {
        String path = call.path();
        if (!path.equals("/") && !path.equals("/index.html")) {
            send(response, callback, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = DemoServer.class.getResourceAsStream("/demo/index.html")) {
            if (in == null) {
                send(response, callback, 500, "text/plain", "index.html missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(response, callback, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private static void send(Response response, Callback callback, int status, String contentType, byte[] body) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.write(true, ByteBuffer.wrap(body), callback);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        if (body == null) {
            return form;
        }
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            form.put(key, value);
        }
        return form;
    }

    private static long parseLong(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
    }
}

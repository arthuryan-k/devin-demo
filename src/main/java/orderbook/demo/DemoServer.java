package orderbook.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
 * <p>Threading: the engine lives inside the {@link Simulator}, and everything that touches it (simulated flow, user
 * commands, building the JSON state) runs on the simulator's single thread. HTTP requests are dispatched on the JDK
 * server's default single thread and hop onto the simulator thread via {@link Simulator#execute}.
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

    private record FeedEntry(long seq, String json) {
    }

    private final HttpServer server;
    private final Simulator simulator;
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
        simulator.addListener(this::onCommand);
        server = HttpServer.create(address, 0);
        server.createContext("/", this::handleStatic);
        server.createContext("/api/book", ex -> handle(ex, this::book));
        server.createContext("/api/orders", ex -> handle(ex, this::orders));
        server.createContext("/api/reset", ex -> handle(ex, this::reset));
        server.createContext("/simulate", ex -> handle(ex, this::simulate));
        server.setExecutor(null);
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

    public void start() {
        server.start();
    }

    /** Stops the HTTP server and shuts down the simulator. */
    public void stop() {
        server.stop(0);
        simulator.close();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    Simulator simulator() {
        return simulator;
    }

    private record Response(int status, String json) {
        static Response ok(String json) {
            return new Response(200, json);
        }

        static Response error(int status, String message) {
            return new Response(status, "{\"error\":" + Json.str(message) + "}");
        }
    }

    @FunctionalInterface
    private interface Handler {
        Response handle(HttpExchange exchange) throws IOException;
    }

    private void handle(HttpExchange exchange, Handler handler) throws IOException {
        Response response;
        try {
            response = handler.handle(exchange);
        } catch (IllegalArgumentException e) {
            response = Response.error(400, e.getMessage());
        } catch (RuntimeException e) {
            response = Response.error(500, String.valueOf(e.getMessage()));
        }
        send(exchange, response.status(), "application/json", response.json().getBytes(StandardCharsets.UTF_8));
    }

    // ---- handlers -------------------------------------------------------------------------------------------------

    private Response book(HttpExchange exchange) {
        if (!exchange.getRequestMethod().equals("GET")) {
            return Response.error(405, "use GET");
        }
        String sinceText = parseForm(exchange.getRequestURI().getRawQuery()).get("since");
        long since = sinceText == null || sinceText.isBlank() ? 0 : parseLong("since", sinceText);
        return Response.ok(simulator.execute(() -> stateJson(since)));
    }

    private Response orders(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (method.equals("POST") && path.equals("/api/orders")) {
            return place(parseForm(body(exchange)));
        }
        if (path.startsWith("/api/orders/")) {
            long id = parseLong("id", path.substring("/api/orders/".length()));
            if (method.equals("DELETE")) {
                return simulator.execute(() -> submitUser(new Command.Cancel(id)));
            }
            if (method.equals("PATCH")) {
                return amend(id, parseForm(body(exchange)));
            }
        }
        return Response.error(405, "use POST /api/orders, PATCH /api/orders/{id} or DELETE /api/orders/{id}");
    }

    private Response place(Map<String, String> form) {
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

    private Response amend(long id, Map<String, String> form) {
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
    private Response submitUser(Command command) {
        Optional<Account.Rejection> rejection = account.admit(command, simulator.engine().book());
        if (rejection.isPresent()) {
            Account.Rejection r = rejection.get();
            String json = Json.obj("type", Json.str("AccountRejected"), "orderId", Long.toString(r.orderId()),
                    "participant", Json.str(Simulator.USER_LABEL), "reason", Json.str(r.reason().name()),
                    "message", Json.str(r.message()));
            addFeed(json);
            return Response.ok("{\"events\":[" + json + "],\"book\":" + stateJson(Long.MAX_VALUE) + "}");
        }
        List<Event> events = simulator.submit(USER, command);
        List<String> eventJson = new ArrayList<>();
        for (Event event : events) {
            eventJson.add(eventJson(event, USER));
        }
        return Response.ok("{\"events\":" + Json.array(eventJson) + ",\"book\":" + stateJson(Long.MAX_VALUE) + "}");
    }

    private Response reset(HttpExchange exchange) {
        if (!exchange.getRequestMethod().equals("POST")) {
            return Response.error(405, "use POST");
        }
        simulator.reset();
        return Response.ok(simulator.execute(() -> {
            account = accountFactory.get();
            trades.clear();
            tradeCount = 0;
            tradedVolume = 0;
            feed.clear();
            return stateJson(Long.MAX_VALUE);
        }));
    }

    private Response simulate(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            return Response.error(405, "use POST");
        }
        switch (exchange.getRequestURI().getPath()) {
            case "/simulate/start" -> simulator.start();
            case "/simulate/stop" -> simulator.stop();
            case "/simulate/rate" -> {
                Map<String, String> params = parseForm(exchange.getRequestURI().getRawQuery());
                params.putAll(parseForm(body(exchange)));
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
                return Response.error(404, "use /simulate/start, /simulate/stop or /simulate/rate?perSec=N");
            }
        }
        return Response.ok(simulator.execute(() -> "{\"simulation\":" + simulationJson() + ",\"book\":"
                + stateJson(Long.MAX_VALUE) + "}"));
    }

    // ---- event feed -----------------------------------------------------------------------------------------------

    /** Simulator listener: settles the account and records trades and events for every command. */
    private void onCommand(long participantId, Command command, List<Event> events) {
        account.onEvents(events);
        for (Event event : events) {
            if (event instanceof Event.TradeExecuted executed) {
                trades.addFirst(tradeJson(executed.trade()));
                tradeCount++;
                tradedVolume += executed.trade().qty();
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

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

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

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = DemoServer.class.getResourceAsStream("/demo/index.html")) {
            if (in == null) {
                send(exchange, 500, "text/plain", "index.html missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
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

    /** Minimal JSON writer; values passed to {@link #obj} and {@link #array} are already-encoded JSON. */
    private static final class Json {
        static String str(String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }

        static String num(OptionalLong value) {
            return value.isPresent() ? Long.toString(value.getAsLong()) : "null";
        }

        static String array(List<String> values) {
            return "[" + String.join(",", values) + "]";
        }

        static String obj(String... keyValues) {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keyValues.length; i += 2) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(str(keyValues[i])).append(':').append(keyValues[i + 1]);
            }
            return sb.append('}').toString();
        }
    }
}

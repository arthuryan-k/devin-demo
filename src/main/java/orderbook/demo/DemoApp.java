package orderbook.demo;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.function.Supplier;

import orderbook.Command;
import orderbook.Event;
import orderbook.Order;
import orderbook.OrderBook;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.Trade;
import orderbook.marketdata.MarketDataPublisher;
import orderbook.marketdata.Outbox;
import orderbook.pipeline.Gateway;
import orderbook.sim.Simulator;

/**
 * The demo backend without any transport: JSON API, the user's {@link Account} (the gateway's pre-trade check for
 * the user) and the {@link MarketDataPublisher} stream. Hosted by {@link DemoServer} on Jetty, or compiled to
 * JavaScript and run in the browser. Account and feed state are confined to the simulator's loop; HTTP threads only
 * wait for their command's result off the loop.
 */
final class DemoApp {

    private static final int MAX_TRADES = 100;
    private static final int MAX_FEED = 500;
    private static final int MAX_FEED_PER_RESPONSE = 200;
    private static final int DEPTH_LEVELS = 30;
    static final long USER = Simulator.USER_PARTICIPANT_ID;
    private static final long RESPONSE_TIMEOUT_MILLIS = 10_000;


    private record FeedEntry(long seq, String json) {
    }

    private final Simulator simulator;
    private final MarketDataPublisher marketData = new MarketDataPublisher();
    private final Supplier<Account> accountFactory;

    // Confined to the simulator thread.
    private Account account;
    private final ArrayDeque<String> trades = new ArrayDeque<>();
    private long tradeCount;
    private long tradedVolume;
    private final ArrayDeque<FeedEntry> feed = new ArrayDeque<>();
    private long feedSeq;

    /** {@code accountFactory} builds the user's account at startup and on reset; null for a random starting cash. */
    DemoApp(Simulator simulator, Supplier<Account> accountFactory) {
        this.simulator = simulator;
        Random accountRandom = new Random(simulator.seed() ^ 0x5DEECE66DL);
        this.accountFactory = accountFactory != null ? accountFactory
                : () -> Account.withRandomCash(USER, accountRandom, simulator.startingReferenceTicks());
        simulator.run(() -> account = this.accountFactory.get());
        marketData.setLabels(simulator::label);
        simulator.pipeline().addConsumer(marketData);
        simulator.gateway().setRiskCheck(USER, new AccountCheck());
        simulator.addListener(this::onCommand);
    }

    /** Closes stream clients and shuts down the simulator. */
    void close() {
        marketData.closeAll();
        simulator.close();
    }

    MarketDataPublisher marketData() {
        return marketData;
    }

    /** The user's reservations, checked before the command is published; runs on the simulator thread. */
    private final class AccountCheck implements Gateway.RiskCheck {
        @Override
        public Gateway.Rejection admit(Command command) {
            return account.admit(command, simulator.engine().book())
                    .map(r -> new Gateway.Rejection(r.orderId(), r.reason().name(), r.message()))
                    .orElse(null);
        }

        @Override
        public void unpublished(Command command, Event.OrderRejected busy) {
            account.onEvents(List.of(busy));
        }
    }

    Simulator simulator() {
        return simulator;
    }

    /** Subscribes to {@code /marketdata/stream}: a snapshot first, then one delta per global sequence. */
    Outbox connect(Outbox.Transport transport) {
        return marketData.subscribe(transport);
    }

    void disconnect(Outbox out) {
        marketData.unsubscribe(out);
    }

    record Reply(int status, String json) {
        static Reply ok(String json) {
            return new Reply(200, json);
        }

        static Reply error(int status, String message) {
            return new Reply(status, "{\"error\":" + Json.str(message) + "}");
        }
    }

    /** One API request; {@code query} and {@code body} are still URL-encoded. */
    record Call(String method, String path, String query, String body) {
    }

    @FunctionalInterface
    private interface Endpoint {
        Reply handle(Call call);
    }

    boolean isApi(String path) {
        return route(path) != null;
    }

    /** Handles an API request; invalid input becomes a 400 reply rather than an exception. */
    Reply handle(String method, String path, String query, String body) {
        Endpoint endpoint = route(path);
        if (endpoint == null) {
            return Reply.error(404, "not found");
        }
        try {
            return endpoint.handle(new Call(method, path, query, body));
        } catch (IllegalArgumentException e) {
            return Reply.error(400, e.getMessage());
        } catch (RuntimeException e) {
            return Reply.error(500, String.valueOf(e.getMessage()));
        }
    }

    private Endpoint route(String path) {
        if (under(path, "/api/book")) {
            return this::book;
        }
        if (under(path, "/api/account")) {
            return this::accountState;
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

    // ---- handlers -------------------------------------------------------------------------------------------------

    private Reply book(Call call) {
        if (!call.method().equals("GET")) {
            return Reply.error(405, "use GET");
        }
        String sinceText = parseForm(call.query()).get("since");
        long since = sinceText == null || sinceText.isBlank() ? 0 : parseLong("since", sinceText);
        return Reply.ok(simulator.execute(() -> stateJson(since)));
    }

    /** Polled by the page: everything that is not the book (the book streams). */
    private Reply accountState(Call call) {
        if (!call.method().equals("GET")) {
            return Reply.error(405, "use GET");
        }
        return Reply.ok(simulator.execute(() -> Json.obj("account", accountJson(), "simulation", simulationJson(),
                "tradeCount", Long.toString(tradeCount), "tradedVolume", Long.toString(tradedVolume),
                "lastTradePrice", price(account.lastTradePrice()))));
    }

    private Reply orders(Call call) {
        String method = call.method();
        String path = call.path();
        if (method.equals("POST") && path.equals("/api/orders")) {
            return place(parseForm(call.body()));
        }
        if (path.startsWith("/api/orders/")) {
            long id = parseLong("id", path.substring("/api/orders/".length()));
            if (method.equals("DELETE")) {
                return submitUser(new Command.Cancel(id));
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

        Gateway gateway = simulator.gateway();
        long id;
        if (requestedId != null) {
            id = requestedId;
            gateway.reserveOrderIdsThrough(id);
        } else {
            id = gateway.nextOrderId();
        }
        Order order = type == OrderType.LIMIT ? Order.limit(id, USER, side, price, qty, tif)
                : Order.market(id, USER, side, qty, tif);
        return submitUser(new Command.Place(order));
    }

    private Reply amend(long id, Map<String, String> form) {
        String priceText = form.getOrDefault("price", "").trim();
        String qtyText = form.getOrDefault("qty", "").trim();
        Long price = priceText.isEmpty() ? null : Ticks.parse("price", priceText);
        Long qty = qtyText.isEmpty() ? null : parseLong("qty", qtyText);
        if (price == null && qty == null) {
            throw new IllegalArgumentException("price or qty is required");
        }
        return submitUser(new Command.Amend(id, price, qty));
    }

    /**
     * HTTP thread: account check and publish on the simulator thread (the account is confined there), then wait for
     * the engine's result through the response router without holding the loop, then reply with the new state.
     */
    private Reply submitUser(Command command) {
        Gateway.Submission submission = simulator.execute(() -> {
            simulator.catchUp();
            return simulator.gateway().submit(USER, command, true);
        });
        if (submission.rejection() != null) {
            Gateway.Rejection r = submission.rejection();
            String json = Json.obj("type", Json.str("AccountRejected"), "orderId", Long.toString(r.orderId()),
                    "participant", Json.str(Simulator.USER_LABEL), "reason", Json.str(r.reason()),
                    "message", Json.str(r.message()));
            return simulator.execute(() -> {
                addFeed(json);
                return Reply.ok("{\"events\":[" + json + "],\"book\":" + stateJson(Long.MAX_VALUE) + "}");
            });
        }
        List<Event> events;
        long seq = submission.seq();
        if (submission.busy() != null) {
            events = List.of(submission.busy());
        } else {
            events = simulator.pipeline().awaitResponse(seq, RESPONSE_TIMEOUT_MILLIS).events();
        }
        return simulator.execute(() -> {
            if (seq >= 0) {
                simulator.awaitApplied(seq);
            }
            List<String> eventJson = new ArrayList<>();
            for (Event event : events) {
                eventJson.add(eventJson(event, USER));
            }
            return Reply.ok("{\"seq\":" + seq + ",\"events\":" + Json.array(eventJson) + ",\"book\":"
                    + stateJson(Long.MAX_VALUE) + "}");
        });
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
            return stateJson(Long.MAX_VALUE);
        }));
    }

    private Reply simulate(Call call) {
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
                "startingCash", Ticks.format(account.startingCash()),
                "avgCost", price(account.averageCost()),
                "realizedPnl", Ticks.format(account.realizedPnl()),
                "unrealizedPnl", Ticks.format(account.unrealizedPnl()),
                "sessionPnl", Ticks.format(account.sessionPnl()),
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

    static Map<String, String> parseForm(String body) {
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

package orderbook.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import orderbook.Command;
import orderbook.Event;
import orderbook.MatchingEngine;
import orderbook.Order;
import orderbook.OrderBook;
import orderbook.Side;
import orderbook.Trade;

/**
 * Local HTTP demo for poking at the matching engine from a browser. Not part of the engine itself.
 *
 * <p>The server uses the JDK's default executor, so every request is handled sequentially on the single dispatcher
 * thread; the engine is never touched concurrently.
 */
public final class DemoServer {

    private static final int MAX_TRADES = 100;

    private final HttpServer server;
    private MatchingEngine engine = new MatchingEngine();
    private final List<Trade> trades = new ArrayList<>();
    private long tradeCount;
    private long tradedVolume;
    private long maxOrderId;

    public DemoServer(InetSocketAddress address) throws IOException {
        server = HttpServer.create(address, 0);
        server.createContext("/", this::handleStatic);
        server.createContext("/api/book", ex -> handle(ex, this::book));
        server.createContext("/api/orders", ex -> handle(ex, this::orders));
        server.createContext("/api/reset", ex -> handle(ex, this::reset));
        server.setExecutor(null);
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        DemoServer demo = new DemoServer(new InetSocketAddress(port));
        demo.start();
        System.out.println("Order book demo running at http://localhost:" + demo.port() + "/");
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
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
        }
        send(exchange, response.status(), "application/json", response.json().getBytes(StandardCharsets.UTF_8));
    }

    private Response book(HttpExchange exchange) {
        if (!exchange.getRequestMethod().equals("GET")) {
            return Response.error(405, "use GET");
        }
        return Response.ok(stateJson());
    }

    private Response orders(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (method.equals("POST") && path.equals("/api/orders")) {
            return place(parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        }
        if (method.equals("DELETE") && path.startsWith("/api/orders/")) {
            long id = parseLong("id", path.substring("/api/orders/".length()));
            return respond(engine.process(new Command.Cancel(id)));
        }
        return Response.error(405, "use POST /api/orders or DELETE /api/orders/{id}");
    }

    private Response place(Map<String, String> form) {
        Side side;
        try {
            side = Side.valueOf(form.getOrDefault("side", "").toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("side must be BUY or SELL");
        }
        long price = parseLong("price", form.get("price"));
        long qty = parseLong("qty", form.get("qty"));
        String idText = form.getOrDefault("id", "").trim();
        long id = idText.isEmpty() ? maxOrderId + 1 : parseLong("id", idText);

        List<Event> events = engine.process(new Command.Place(new Order(id, side, price, qty)));
        for (Event event : events) {
            if (event instanceof Event.OrderPlaced) {
                maxOrderId = Math.max(maxOrderId, id);
            }
        }
        return respond(events);
    }

    private Response reset(HttpExchange exchange) {
        if (!exchange.getRequestMethod().equals("POST")) {
            return Response.error(405, "use POST");
        }
        engine = new MatchingEngine();
        trades.clear();
        tradeCount = 0;
        tradedVolume = 0;
        maxOrderId = 0;
        return Response.ok(stateJson());
    }

    private Response respond(List<Event> events) {
        for (Event event : events) {
            if (event instanceof Event.TradeExecuted executed) {
                trades.add(executed.trade());
                tradeCount++;
                tradedVolume += executed.trade().qty();
            }
        }
        if (trades.size() > MAX_TRADES) {
            trades.subList(0, trades.size() - MAX_TRADES).clear();
        }
        List<String> eventJson = new ArrayList<>();
        for (Event event : events) {
            eventJson.add(eventJson(event));
        }
        return Response.ok("{\"events\":" + Json.array(eventJson) + ",\"book\":" + stateJson() + "}");
    }

    private String stateJson() {
        OrderBook book = engine.book();
        OptionalLong bid = book.bestBid();
        OptionalLong ask = book.bestAsk();
        List<String> recent = new ArrayList<>();
        for (int i = trades.size() - 1; i >= 0; i--) {
            recent.add(tradeJson(trades.get(i)));
        }
        return Json.obj(
                "bestBid", Json.num(bid),
                "bestAsk", Json.num(ask),
                "spread", bid.isPresent() && ask.isPresent() ? Long.toString(ask.getAsLong() - bid.getAsLong()) : "null",
                "bids", levelsJson(book.orders(Side.BUY)),
                "asks", levelsJson(book.orders(Side.SELL)),
                "trades", Json.array(recent),
                "tradeCount", Long.toString(tradeCount),
                "tradedVolume", Long.toString(tradedVolume),
                "nextOrderId", Long.toString(maxOrderId + 1));
    }

    /** Groups orders (already in priority order) into levels, keeping FIFO order within each level. */
    private static String levelsJson(List<Order> orders) {
        List<String> levels = new ArrayList<>();
        int i = 0;
        while (i < orders.size()) {
            long price = orders.get(i).price();
            long total = 0;
            List<String> orderJson = new ArrayList<>();
            while (i < orders.size() && orders.get(i).price() == price) {
                Order o = orders.get(i++);
                total += o.qtyRemaining();
                orderJson.add(Json.obj("id", Long.toString(o.id()), "qty", Long.toString(o.qtyRemaining()),
                        "seqNum", Long.toString(o.seqNum())));
            }
            levels.add(Json.obj("price", Long.toString(price), "totalQty", Long.toString(total),
                    "orders", Json.array(orderJson)));
        }
        return Json.array(levels);
    }

    private static String tradeJson(Trade t) {
        return Json.obj("makerOrderId", Long.toString(t.makerOrderId()), "takerOrderId", Long.toString(t.takerOrderId()),
                "price", Long.toString(t.price()), "qty", Long.toString(t.qty()));
    }

    private static String eventJson(Event event) {
        if (event instanceof Event.OrderPlaced e) {
            return Json.obj("type", Json.str("OrderPlaced"), "orderId", Long.toString(e.orderId()),
                    "side", Json.str(e.side().name()), "price", Long.toString(e.price()), "qty", Long.toString(e.qty()));
        }
        if (event instanceof Event.OrderRejected e) {
            return Json.obj("type", Json.str("OrderRejected"), "orderId", Long.toString(e.orderId()),
                    "reason", Json.str(e.reason().name()));
        }
        if (event instanceof Event.OrderCancelled e) {
            return Json.obj("type", Json.str("OrderCancelled"), "orderId", Long.toString(e.orderId()),
                    "cancelledQty", Long.toString(e.cancelledQty()));
        }
        if (event instanceof Event.TradeExecuted e) {
            return Json.obj("type", Json.str("TradeExecuted"), "trade", tradeJson(e.trade()));
        }
        throw new IllegalStateException("unknown event " + event);
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

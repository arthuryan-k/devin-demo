package simulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Public market data, as any client sees it: one WebSocket per symbol to the market-data service (through the
 * gateway, {@code /marketdata/<SYM>/ws?prices=ticks}), keeping a local L2 replica from the snapshot + contiguous
 * deltas, and passing trade prints (with maker/taker order ids) to listeners. A gap triggers {@code resync}; a dropped
 * socket reconnects.
 */
public final class MarketDataFeed implements MarketView, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MarketDataFeed.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    static final class Book {
        final NavigableMap<Long, Level> bids = new TreeMap<>(Collections.reverseOrder());
        final NavigableMap<Long, Level> asks = new TreeMap<>();
        long seq = -1;
    }

    private final String wsBase;
    private final HttpClient http;
    private final Map<String, Book> books = new HashMap<>();
    private final List<TradeListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService reconnects = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "md-reconnect");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, WebSocket> sockets = new HashMap<>();
    private volatile boolean closed;

    public MarketDataFeed(String gatewayUrl, HttpClient http, List<String> symbols) {
        this.wsBase = gatewayUrl.replaceAll("/+$", "").replaceFirst("^http", "ws");
        this.http = http;
        for (String s : symbols) {
            books.put(s, new Book());
        }
    }

    /** Opens every symbol's socket; returns once each is connected (or has scheduled a retry). */
    public MarketDataFeed connect() {
        for (String s : books.keySet()) {
            open(s);
        }
        return this;
    }

    private void open(String symbol) {
        if (closed) {
            return;
        }
        URI uri = URI.create(wsBase + "/marketdata/" + symbol + "/ws?prices=ticks");
        http.newWebSocketBuilder().buildAsync(uri, new Listener(symbol)).whenComplete((ws, err) -> {
            if (err != null) {
                LOG.warning("market data " + symbol + ": " + err.getMessage() + "; retrying");
                reconnects.schedule(() -> open(symbol), 1, TimeUnit.SECONDS);
            } else {
                synchronized (sockets) {
                    sockets.put(symbol, ws);
                }
            }
        });
    }

    private final class Listener implements WebSocket.Listener {
        private final String symbol;
        private final StringBuilder partial = new StringBuilder();

        Listener(String symbol) {
            this.symbol = symbol;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    if (!apply(JSON.readTree(text))) {
                        ws.sendText("{\"type\":\"resync\"}", true);
                    }
                } catch (Exception e) {
                    LOG.warning("bad market data on " + symbol + ": " + e);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            reconnects.schedule(() -> open(symbol), 1, TimeUnit.SECONDS);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            reconnects.schedule(() -> open(symbol), 1, TimeUnit.SECONDS);
        }
    }

    /** Applies one feed message (prices in ticks). @return false on a sequence gap (caller should resync) */
    boolean apply(JsonNode msg) {
        String symbol = msg.path("symbol").asText();
        Book book = books.get(symbol);
        if (book == null) {
            return true;
        }
        String type = msg.path("type").asText();
        long seq = msg.path("seq").asLong();
        List<Trade> trades = new ArrayList<>();
        synchronized (book) {
            if ("snapshot".equals(type)) {
                book.bids.clear();
                book.asks.clear();
                for (JsonNode l : msg.path("bids")) {
                    book.bids.put(l.get(0).asLong(), new Level(l.get(0).asLong(), l.get(1).asLong(), l.get(2).asInt()));
                }
                for (JsonNode l : msg.path("asks")) {
                    book.asks.put(l.get(0).asLong(), new Level(l.get(0).asLong(), l.get(1).asLong(), l.get(2).asInt()));
                }
                book.seq = seq;
            } else if ("delta".equals(type)) {
                if (book.seq >= 0 && seq != book.seq + 1) {
                    return seq <= book.seq; // duplicate: ignore; gap: resync
                }
                book.seq = seq;
                for (JsonNode l : msg.path("levels")) {
                    NavigableMap<Long, Level> side = "B".equals(l.get(1).asText()) ? book.bids : book.asks;
                    long px = l.get(2).asLong();
                    if ("r".equals(l.get(0).asText()) || l.get(3).asLong() <= 0) {
                        side.remove(px);
                    } else {
                        side.put(px, new Level(px, l.get(3).asLong(), l.get(4).asInt()));
                    }
                }
                for (JsonNode e : msg.path("events")) {
                    if ("TradeExecuted".equals(e.path("type").asText())) {
                        trades.add(new Trade(symbol, e.path("makerOrderId").asLong(), e.path("takerOrderId").asLong(),
                                e.path("price").asLong(), e.path("qty").asLong()));
                    }
                }
            }
        }
        for (Trade t : trades) {
            for (TradeListener l : listeners) {
                l.onTrade(t);
            }
        }
        return true;
    }

    @Override
    public List<Level> depth(String symbol, Side side, int levels) {
        Book book = books.get(symbol);
        if (book == null) {
            return List.of();
        }
        synchronized (book) {
            List<Level> out = new ArrayList<>(levels);
            for (Level l : (side == Side.BUY ? book.bids : book.asks).values()) {
                if (out.size() == levels) {
                    break;
                }
                out.add(l);
            }
            return out;
        }
    }

    @Override
    public void addTradeListener(TradeListener listener) {
        listeners.add(listener);
    }

    @Override
    public void close() {
        closed = true;
        reconnects.shutdownNow();
        synchronized (sockets) {
            sockets.values().forEach(ws -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"));
        }
    }
}

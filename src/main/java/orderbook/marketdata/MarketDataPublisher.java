package orderbook.marketdata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongFunction;

import orderbook.Command;
import orderbook.Event;
import orderbook.Order;
import orderbook.OrderBook;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.Trade;
import orderbook.pipeline.ResultEvent;
import orderbook.pipeline.ResultHandler;

/**
 * Output-ring consumer that turns engine events into a market-data stream: aggregated price levels (L2) and trade
 * prints, one message per global sequence number.
 *
 * <p>Messages (JSON, prices in integer ticks):
 * <ul>
 *   <li>{@code {"type":"snapshot","seq":S,"reason":"connect|resync|reset","bids":[[price,qty,orders],...],
 *       "asks":[...],"trades":[...],"events":[...]}} — the book as of {@code seq} S (best level first).</li>
 *   <li>{@code {"type":"delta","seq":N,"levels":[[op,side,price,qty,orders],...],"events":[...]}} — what command
 *       N changed; {@code op} is {@code "a"} (add), {@code "u"} (update) or {@code "r"} (remove), {@code side}
 *       {@code "B"}/{@code "S"}. Trade prints are {@code TradeExecuted} entries in {@code events}.</li>
 * </ul>
 *
 * <p>Every sequenced command yields exactly one delta (possibly with no level changes, e.g. a rejection), so
 * sequence numbers are contiguous: a subscriber applies a snapshot S, then expects deltas S+1, S+2, …; any other
 * sequence is a gap and the client must resync from a fresh snapshot. A subscription's first message is always a
 * snapshot taken under the same lock that applies results, so nothing between snapshot and first delta is lost or
 * duplicated.
 *
 * <p>The publisher keeps its own order-level (L3) shadow of the book, derived purely from events, to know each
 * level's quantity and order count. Delivery to clients goes through per-client {@link Outbox}es; a slow client is
 * resynced with a snapshot and never blocks this handler.
 */
public final class MarketDataPublisher implements ResultHandler {

    public static final int MAX_QUEUED_PER_CLIENT = 1024;
    private static final int MAX_RECENT_TRADES = 50;
    private static final int MAX_RECENT_EVENTS = 100;

    private static final class Resting {
        final Side side;
        final long price;
        final long participantId;
        long qty;

        Resting(Side side, long price, long qty, long participantId) {
            this.side = side;
            this.price = price;
            this.qty = qty;
            this.participantId = participantId;
        }
    }

    /** The incoming (aggressive) order of the command being applied. */
    private static final class Taker {
        long id;
        Side side;
        long price;
        long remaining;
        long participantId;
        boolean limit;
        boolean accepted;
        boolean cancelled;
    }

    private record LevelKey(Side side, long price) {
    }

    private final Map<Long, Resting> orders = new HashMap<>();
    private final TreeMap<Long, long[]> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, long[]> asks = new TreeMap<>();
    private final ArrayDeque<String> recentTrades = new ArrayDeque<>();
    private final ArrayDeque<String> recentEvents = new ArrayDeque<>();
    private final List<Outbox> clients = new CopyOnWriteArrayList<>();
    private volatile LongFunction<String> labels = id -> "P" + id;
    private long seq = -1;
    private long tradeN;

    /** How participant ids are shown in event and trade prints. Must be thread-safe. */
    public void setLabels(LongFunction<String> labels) {
        this.labels = labels;
    }

    // ---- subscription ---------------------------------------------------------------------------------------------

    /** Subscribes a client; its first message is a snapshot, then every delta in order. */
    public synchronized Outbox subscribe(Outbox.Transport transport) {
        Outbox out = new Outbox(transport, MAX_QUEUED_PER_CLIENT);
        out.enqueue(snapshot("connect"), this::resyncSnapshot);
        clients.add(out);
        return out;
    }

    public void unsubscribe(Outbox out) {
        clients.remove(out);
        out.close();
    }

    public int subscribers() {
        return clients.size();
    }

    /** Queues an empty keepalive frame to every client (in order with the data) and drops closed clients. */
    public void heartbeat() {
        for (Outbox out : clients) {
            if (out.isClosed()) {
                clients.remove(out);
            } else {
                out.enqueue("", this::resyncSnapshot);
            }
        }
    }

    public void closeAll() {
        for (Outbox out : clients) {
            unsubscribe(out);
        }
    }

    public synchronized String snapshot() {
        return snapshot("connect");
    }

    /** Last applied global sequence, or -1. */
    public synchronized long seq() {
        return seq;
    }

    /** Aggregated levels, best first, as {@link OrderBook.Level}s; for checks against the engine's book. */
    public synchronized List<OrderBook.Level> levels(Side side) {
        List<OrderBook.Level> out = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : (side == Side.BUY ? bids : asks).entrySet()) {
            out.add(new OrderBook.Level(e.getKey(), e.getValue()[0], (int) e.getValue()[1]));
        }
        return out;
    }

    private String resyncSnapshot() {
        synchronized (this) {
            return snapshot("resync");
        }
    }

    // ---- applying results -----------------------------------------------------------------------------------------

    @Override
    public synchronized void onResult(ResultEvent result, boolean endOfBatch) {
        seq = result.seq();
        if (result.isReset()) {
            orders.clear();
            bids.clear();
            asks.clear();
            recentTrades.clear();
            recentEvents.clear();
            tradeN = 0;
            broadcast(snapshot("reset"));
            return;
        }
        broadcast(apply(result.participantId(), result.command(), result.events()));
    }

    private void broadcast(String message) {
        for (Outbox out : clients) {
            out.enqueue(message, this::resyncSnapshot);
        }
    }

    /** Applies one command's events to the shadow book and renders the delta message. */
    private String apply(long submitter, Command command, List<Event> events) {
        Map<LevelKey, long[]> before = new LinkedHashMap<>();
        List<String> eventJson = new ArrayList<>();
        Taker taker = null;
        if (command instanceof Command.Place place) {
            Order o = place.order();
            taker = new Taker();
            taker.id = o.id();
            taker.side = o.side();
            taker.price = o.price();
            taker.remaining = o.qtyRemaining();
            taker.participantId = submitter;
            taker.limit = o.type() == OrderType.LIMIT;
        }
        for (Event event : events) {
            if (event instanceof Event.OrderPlaced e) {
                if (taker != null && taker.id == e.orderId()) {
                    taker.accepted = true;
                }
                eventJson.add("{\"type\":\"OrderPlaced\",\"orderId\":" + e.orderId()
                        + ",\"participant\":" + quote(label(submitter)) + ",\"side\":\"" + e.side() + "\",\"price\":"
                        + (taker != null && !taker.limit ? "null" : Long.toString(e.price())) + ",\"qty\":" + e.qty()
                        + "}");
            } else if (event instanceof Event.OrderRejected e) {
                eventJson.add("{\"type\":\"OrderRejected\",\"orderId\":" + e.orderId()
                        + ",\"participant\":" + quote(label(submitter)) + ",\"reason\":\"" + e.reason() + "\"}");
            } else if (event instanceof Event.OrderCancelled e) {
                Resting r = orders.get(e.orderId());
                long owner = submitter;
                if (taker != null && taker.id == e.orderId()) {
                    taker.cancelled = true;
                } else if (r != null) {
                    owner = r.participantId;
                    removeResting(e.orderId(), r, before);
                }
                eventJson.add("{\"type\":\"OrderCancelled\",\"orderId\":" + e.orderId() + ",\"participant\":"
                        + quote(label(owner)) + ",\"cancelledQty\":" + e.cancelledQty() + ",\"reason\":\""
                        + e.reason() + "\"}");
            } else if (event instanceof Event.OrderAmended e) {
                Resting r = orders.get(e.orderId());
                if (r != null) {
                    if (e.priorityRetained()) {
                        changeLevel(r.side, r.price, e.newQty() - r.qty, 0, before);
                        r.qty = e.newQty();
                    } else {
                        removeResting(e.orderId(), r, before);
                        taker = new Taker();
                        taker.id = e.orderId();
                        taker.side = r.side;
                        taker.price = e.newPrice();
                        taker.remaining = e.newQty();
                        taker.participantId = r.participantId;
                        taker.limit = true;
                        taker.accepted = true;
                    }
                    eventJson.add("{\"type\":\"OrderAmended\",\"orderId\":" + e.orderId() + ",\"participant\":"
                            + quote(label(r.participantId)) + ",\"oldPrice\":" + e.oldPrice() + ",\"newPrice\":"
                            + e.newPrice() + ",\"oldQty\":" + e.oldQty() + ",\"newQty\":" + e.newQty()
                            + ",\"priorityRetained\":" + e.priorityRetained() + "}");
                }
            } else if (event instanceof Event.TradeExecuted e) {
                eventJson.add(trade(e.trade(), taker, before));
            }
        }
        if (taker != null && taker.accepted && taker.limit && !taker.cancelled && taker.remaining > 0) {
            orders.put(taker.id, new Resting(taker.side, taker.price, taker.remaining, taker.participantId));
            changeLevel(taker.side, taker.price, taker.remaining, 1, before);
        }
        for (String e : eventJson) {
            remember(recentEvents, e, MAX_RECENT_EVENTS);
        }
        StringBuilder sb = new StringBuilder(128).append("{\"type\":\"delta\",\"seq\":").append(seq)
                .append(",\"levels\":[");
        boolean first = true;
        for (Map.Entry<LevelKey, long[]> change : before.entrySet()) {
            LevelKey key = change.getKey();
            long[] was = change.getValue();
            long[] now = (key.side() == Side.BUY ? bids : asks).get(key.price());
            String op;
            if (was == null && now == null) {
                continue;
            } else if (was == null) {
                op = "a";
            } else if (now == null) {
                op = "r";
            } else if (was[0] == now[0] && was[1] == now[1]) {
                continue;
            } else {
                op = "u";
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("[\"").append(op).append("\",\"").append(key.side() == Side.BUY ? 'B' : 'S').append("\",")
                    .append(key.price()).append(',').append(now == null ? 0 : now[0]).append(',')
                    .append(now == null ? 0 : now[1]).append(']');
        }
        sb.append("],\"events\":[").append(String.join(",", eventJson)).append("]}");
        return sb.toString();
    }

    private String trade(Trade t, Taker taker, Map<LevelKey, long[]> before) {
        Resting maker = orders.get(t.makerOrderId());
        long makerParticipant = maker != null ? maker.participantId : Order.NO_PARTICIPANT;
        if (maker != null) {
            if (maker.qty <= t.qty()) {
                removeResting(t.makerOrderId(), maker, before);
            } else {
                maker.qty -= t.qty();
                changeLevel(maker.side, maker.price, -t.qty(), 0, before);
            }
        }
        Side takerSide = null;
        long takerParticipant = Order.NO_PARTICIPANT;
        if (taker != null && taker.id == t.takerOrderId()) {
            taker.remaining -= t.qty();
            takerSide = taker.side;
            takerParticipant = taker.participantId;
        }
        String json = "{\"type\":\"TradeExecuted\",\"n\":" + (++tradeN) + ",\"price\":" + t.price() + ",\"qty\":"
                + t.qty() + ",\"takerSide\":" + (takerSide == null ? "null" : "\"" + takerSide + "\"")
                + ",\"makerOrderId\":" + t.makerOrderId() + ",\"takerOrderId\":" + t.takerOrderId()
                + ",\"maker\":" + quote(label(makerParticipant)) + ",\"taker\":" + quote(label(takerParticipant))
                + "}";
        remember(recentTrades, json, MAX_RECENT_TRADES);
        return json;
    }

    private void removeResting(long orderId, Resting r, Map<LevelKey, long[]> before) {
        orders.remove(orderId);
        changeLevel(r.side, r.price, -r.qty, -1, before);
    }

    private void changeLevel(Side side, long price, long dQty, int dCount, Map<LevelKey, long[]> before) {
        TreeMap<Long, long[]> levels = side == Side.BUY ? bids : asks;
        long[] level = levels.get(price);
        LevelKey key = new LevelKey(side, price);
        if (!before.containsKey(key)) {
            before.put(key, level == null ? null : level.clone());
        }
        if (level == null) {
            level = new long[2];
            levels.put(price, level);
        }
        level[0] += dQty;
        level[1] += dCount;
        if (level[1] <= 0) {
            levels.remove(price);
        }
    }

    private static void remember(ArrayDeque<String> list, String json, int max) {
        list.addLast(json);
        while (list.size() > max) {
            list.removeFirst();
        }
    }

    private String label(long participantId) {
        return labels.apply(participantId);
    }

    private String snapshot(String reason) {
        StringBuilder sb = new StringBuilder(1024).append("{\"type\":\"snapshot\",\"seq\":").append(seq)
                .append(",\"reason\":\"").append(reason).append("\",\"bids\":");
        levelsJson(sb, bids);
        sb.append(",\"asks\":");
        levelsJson(sb, asks);
        sb.append(",\"trades\":[").append(String.join(",", recentTrades)).append("],\"events\":[")
                .append(String.join(",", recentEvents)).append("]}");
        return sb.toString();
    }

    private static void levelsJson(StringBuilder sb, TreeMap<Long, long[]> levels) {
        sb.append('[');
        boolean first = true;
        for (Map.Entry<Long, long[]> e : levels.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('[').append(e.getKey()).append(',').append(e.getValue()[0]).append(',').append(e.getValue()[1])
                    .append(']');
        }
        sb.append(']');
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
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
}

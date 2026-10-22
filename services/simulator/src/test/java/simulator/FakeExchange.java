package simulator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory stand-in for auth + gateway + market data, used to test the simulator without a network: a price-time
 * book per symbol, one account per sign-up, and a log of every illegal request (unknown symbol, bad price/qty,
 * touching someone else's order).
 */
final class FakeExchange implements ExchangeClient, MarketView {

    record Resting(long id, long participantId, Side side, long price, long qty) {
    }

    private final Set<String> symbols;
    private final Random noise;
    private final Map<String, Long> keyToId = new HashMap<>();
    private final Map<Long, String> names = new HashMap<>();
    private final Map<String, Map<Long, Resting>> books = new HashMap<>();
    private final List<TradeListener> listeners = new CopyOnWriteArrayList<>();
    final List<String> violations = new ArrayList<>();
    final List<Trade> trades = new ArrayList<>();
    final Map<String, Integer> placesBySymbol = new TreeMap<>();
    private long nextId = 1;
    private long nextAccount = 5001;

    /** {@code noise} randomly rejects some requests (simulating network/admission outcomes); null for none. */
    FakeExchange(Set<String> symbols, Random noise) {
        this.symbols = symbols;
        this.noise = noise;
        for (String s : symbols) {
            books.put(s, new LinkedHashMap<>());
        }
    }

    synchronized List<Resting> resting(long participantId) {
        List<Resting> out = new ArrayList<>();
        books.values().forEach(b -> b.values().stream().filter(r -> r.participantId() == participantId)
                .forEach(out::add));
        return out;
    }

    synchronized String nameOf(long participantId) {
        return names.get(participantId);
    }

    @Override
    public synchronized Credentials register(String name) {
        if (names.containsValue(name)) {
            violations.add("duplicate sign-up " + name);
        }
        long id = nextAccount++;
        String key = "key-" + id;
        keyToId.put(key, id);
        names.put(id, name);
        return new Credentials(id, name + "-" + id, key); // auth-service labels accounts <name>-<participantId>
    }

    private Long auth(String key) {
        Long id = keyToId.get(key);
        if (id == null) {
            violations.add("bad key " + key);
        }
        return id;
    }

    private boolean dropped() {
        return noise != null && noise.nextDouble() < 0.1;
    }

    @Override
    public synchronized Reply place(String apiKey, String symbol, Side side, OrderType type, long price, long qty,
            TimeInForce tif) {
        Long pid = auth(apiKey);
        if (pid == null) {
            return Reply.failed(401, "unauthorized");
        }
        if (!symbols.contains(symbol) || qty <= 0 || (type == OrderType.LIMIT && price <= 0)) {
            violations.add("illegal place " + symbol + " " + side + " " + type + " " + price + "x" + qty);
            return Reply.failed(400, "invalid");
        }
        if (dropped()) {
            return Reply.failed(429, "rate limited");
        }
        placesBySymbol.merge(symbol, 1, Integer::sum);
        long id = nextId++;
        Map<Long, Resting> book = books.get(symbol);
        long left = qty;
        List<Resting> opposite = book.values().stream().filter(r -> r.side() != side)
                .filter(r -> type == OrderType.MARKET || (side == Side.BUY ? r.price() <= price : r.price() >= price))
                .sorted(side == Side.BUY ? Comparator.comparingLong(Resting::price)
                        : Comparator.comparingLong(Resting::price).reversed())
                .toList();
        for (Resting r : opposite) {
            if (left == 0) {
                break;
            }
            if (r.participantId() == pid) {
                continue;
            }
            long q = Math.min(left, r.qty());
            left -= q;
            if (q == r.qty()) {
                book.remove(r.id());
            } else {
                book.put(r.id(), new Resting(r.id(), r.participantId(), r.side(), r.price(), r.qty() - q));
            }
            Trade t = new Trade(symbol, r.id(), id, r.price(), q);
            trades.add(t);
            listeners.forEach(l -> l.onTrade(t));
        }
        long resting = 0;
        if (left > 0 && type == OrderType.LIMIT && tif == TimeInForce.GTC) {
            book.put(id, new Resting(id, pid, side, price, left));
            resting = left;
        }
        return new Reply(200, id, qty - left, resting, null);
    }

    private Resting own(long pid, String symbol, long orderId, String what) {
        Map<Long, Resting> book = books.get(symbol);
        Resting r = book == null ? null : book.get(orderId);
        if (r != null && r.participantId() != pid) {
            violations.add(what + " of someone else's order " + orderId);
            return null;
        }
        return r;
    }

    @Override
    public synchronized Reply cancel(String apiKey, String symbol, long orderId) {
        Long pid = auth(apiKey);
        if (pid == null) {
            return Reply.failed(401, "unauthorized");
        }
        Resting r = own(pid, symbol, orderId, "cancel");
        if (r == null) {
            return Reply.failed(404, "unknown order");
        }
        books.get(symbol).remove(orderId);
        return new Reply(200, orderId, 0, 0, null);
    }

    @Override
    public synchronized Reply amend(String apiKey, String symbol, long orderId, Long newPrice, Long newQty) {
        Long pid = auth(apiKey);
        if (pid == null) {
            return Reply.failed(401, "unauthorized");
        }
        if ((newPrice != null && newPrice <= 0) || (newQty != null && newQty <= 0)) {
            violations.add("illegal amend " + newPrice + "x" + newQty);
            return Reply.failed(400, "invalid");
        }
        Resting r = own(pid, symbol, orderId, "amend");
        if (r == null) {
            return Reply.failed(404, "unknown order");
        }
        if (dropped()) {
            return Reply.failed(429, "rate limited");
        }
        Map<Long, Resting> book = books.get(symbol);
        book.remove(orderId);
        Resting next = new Resting(orderId, pid, r.side(), newPrice != null ? newPrice : r.price(),
                newQty != null ? newQty : r.qty());
        book.put(orderId, next);
        return new Reply(200, orderId, 0, next.qty(), null);
    }

    @Override
    public synchronized List<Level> depth(String symbol, Side side, int levels) {
        TreeMap<Long, long[]> agg = new TreeMap<>(side == Side.BUY ? Comparator.<Long>reverseOrder()
                : Comparator.<Long>naturalOrder());
        for (Resting r : books.get(symbol).values()) {
            if (r.side() == side) {
                long[] a = agg.computeIfAbsent(r.price(), k -> new long[2]);
                a[0] += r.qty();
                a[1]++;
            }
        }
        List<Level> out = new ArrayList<>();
        agg.forEach((p, a) -> {
            if (out.size() < levels) {
                out.add(new Level(p, a[0], (int) a[1]));
            }
        });
        return out;
    }

    @Override
    public void addTradeListener(TradeListener listener) {
        listeners.add(listener);
    }
}

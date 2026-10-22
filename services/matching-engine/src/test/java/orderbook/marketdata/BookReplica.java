package orderbook.marketdata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import orderbook.OrderBook;
import orderbook.Side;

/** Test client mirroring the page's logic: snapshot + contiguous deltas, gap → wait for a resync snapshot. */
final class BookReplica {

    private static final Pattern SEQ = Pattern.compile("\"seq\":(-?\\d+)");
    private static final Pattern LEVEL = Pattern.compile("\\[(\\d+),(\\d+),(\\d+)\\]");
    private static final Pattern DELTA = Pattern.compile("\\[\"([aur])\",\"([BS])\",(\\d+),(\\d+),(\\d+)\\]");

    private final TreeMap<Long, long[]> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, long[]> asks = new TreeMap<>();
    private long seq = Long.MIN_VALUE;
    private boolean stale = true;
    int gaps;
    int snapshots;

    static long seqOf(String message) {
        Matcher m = SEQ.matcher(message);
        if (!m.find()) {
            throw new IllegalArgumentException(message);
        }
        return Long.parseLong(m.group(1));
    }

    /** Applies one stream message; returns false if it revealed a gap (the replica now needs a snapshot). */
    boolean accept(String message) {
        long s = seqOf(message);
        if (message.startsWith("{\"type\":\"snapshot\"")) {
            bids.clear();
            asks.clear();
            int bidsAt = message.indexOf("\"bids\":");
            int asksAt = message.indexOf("\"asks\":");
            int tradesAt = message.indexOf(",\"trades\":");
            load(bids, message.substring(bidsAt, asksAt));
            load(asks, message.substring(asksAt, tradesAt));
            seq = s;
            stale = false;
            snapshots++;
            return true;
        }
        if (stale) {
            return true;
        }
        if (s != seq + 1) {
            stale = true;
            gaps++;
            return false;
        }
        Matcher m = DELTA.matcher(message.substring(message.indexOf("\"levels\":"), message.indexOf(",\"events\":")));
        while (m.find()) {
            TreeMap<Long, long[]> side = m.group(2).equals("B") ? bids : asks;
            long price = Long.parseLong(m.group(3));
            if (m.group(1).equals("r")) {
                side.remove(price);
            } else {
                side.put(price, new long[] { Long.parseLong(m.group(4)), Long.parseLong(m.group(5)) });
            }
        }
        seq = s;
        return true;
    }

    private static void load(TreeMap<Long, long[]> side, String json) {
        Matcher m = LEVEL.matcher(json);
        while (m.find()) {
            side.put(Long.parseLong(m.group(1)), new long[] { Long.parseLong(m.group(2)), Long.parseLong(m.group(3)) });
        }
    }

    boolean stale() {
        return stale;
    }

    long seq() {
        return seq;
    }

    List<OrderBook.Level> levels(Side side) {
        List<OrderBook.Level> out = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : (side == Side.BUY ? bids : asks).entrySet()) {
            out.add(new OrderBook.Level(e.getKey(), e.getValue()[0], (int) e.getValue()[1]));
        }
        return out;
    }
}

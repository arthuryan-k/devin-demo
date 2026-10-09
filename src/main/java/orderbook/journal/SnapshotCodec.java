package orderbook.journal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import orderbook.EngineState;
import orderbook.EngineState.RestingOrder;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;

/**
 * Single-line JSON form of a {@link Snapshot}, in the same hand-rolled, dependency-free style as
 * {@link CommandCodec}. Accepted IDs are stored as inclusive {@code [lo,hi]} ranges (IDs are mostly contiguous);
 * each resting order is {@code [id,participant,type,tif,price,qtyRemaining,seqNum]}, listed in priority order.
 *
 * <pre>
 * {"format":2,"seq":41,"state":{"nextSeqNum":30,"maxOrderIdSeen":33,"acceptedIds":[[1,29],[33,33]],
 *  "bids":[[12,7,"LIMIT","GTC",99,10,4]],"asks":[[20,8,"LIMIT","GTC",101,5,17]]}}
 * </pre>
 *
 * {@link #encodeState} is the cheap part done on the engine thread; {@link #encode(long, String)} wraps an
 * encoded state with the sequence number it covers.
 */
public final class SnapshotCodec {

    public static final int FORMAT = 2;

    private SnapshotCodec() {
    }

    public static String encode(Snapshot snapshot) {
        return encode(snapshot.seq(), encodeState(snapshot.state()));
    }

    /** Wraps a state produced by {@link #encodeState}. */
    public static String encode(long seq, String encodedState) {
        return "{\"format\":" + FORMAT + ",\"seq\":" + seq + ",\"state\":" + encodedState + "}";
    }

    public static String encodeState(EngineState state) {
        StringBuilder sb = new StringBuilder(64 + 48 * state.orderCount());
        sb.append("{\"nextSeqNum\":").append(state.nextSeqNum())
                .append(",\"maxOrderIdSeen\":").append(state.maxOrderIdSeen())
                .append(",\"acceptedIds\":[");
        long[] ids = state.acceptedIds();
        for (int i = 0; i < ids.length; ) {
            int j = i;
            while (j + 1 < ids.length && ids[j + 1] == ids[j] + 1) {
                j++;
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(ids[i]).append(',').append(ids[j]).append(']');
            i = j + 1;
        }
        sb.append("],\"bids\":");
        appendOrders(sb, state.bids());
        sb.append(",\"asks\":");
        appendOrders(sb, state.asks());
        return sb.append('}').toString();
    }

    private static void appendOrders(StringBuilder sb, List<RestingOrder> orders) {
        sb.append('[');
        for (int i = 0; i < orders.size(); i++) {
            RestingOrder o = orders.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(o.id()).append(',').append(o.participantId()).append(",\"").append(o.type())
                    .append("\",\"").append(o.timeInForce()).append("\",").append(o.price()).append(',')
                    .append(o.qtyRemaining()).append(',').append(o.seqNum()).append(']');
        }
        sb.append(']');
    }

    /** @throws IllegalArgumentException if {@code json} is not a well-formed snapshot of a supported format */
    public static Snapshot decode(String json) {
        Map<String, Object> top = object(new Reader(json).readDocument(), "snapshot");
        long format = number(top, "format");
        if (format != FORMAT) {
            throw new IllegalArgumentException("unsupported snapshot format " + format + " (expected " + FORMAT + ")");
        }
        Map<String, Object> s = object(top.get("state"), "state");
        List<Long> ids = new ArrayList<>();
        for (Object range : list(s.get("acceptedIds"), "acceptedIds")) {
            List<Object> r = list(range, "acceptedIds range");
            if (r.size() != 2) {
                throw new IllegalArgumentException("bad acceptedIds range " + r);
            }
            long lo = number(r.get(0)), hi = number(r.get(1));
            if (hi < lo || (!ids.isEmpty() && lo <= ids.get(ids.size() - 1))) {
                throw new IllegalArgumentException("acceptedIds ranges must be sorted and disjoint: " + r);
            }
            for (long id = lo; ; id++) {
                ids.add(id);
                if (id == hi) {
                    break;
                }
            }
        }
        long[] accepted = new long[ids.size()];
        for (int i = 0; i < accepted.length; i++) {
            accepted[i] = ids.get(i);
        }
        EngineState state = new EngineState(orders(s.get("bids"), Side.BUY), orders(s.get("asks"), Side.SELL),
                accepted, number(s, "nextSeqNum"), number(s, "maxOrderIdSeen"));
        return new Snapshot(number(top, "seq"), state);
    }

    private static List<RestingOrder> orders(Object value, Side side) {
        List<RestingOrder> result = new ArrayList<>();
        for (Object o : list(value, side + " orders")) {
            List<Object> f = list(o, "order");
            if (f.size() != 7) {
                throw new IllegalArgumentException("order needs 7 fields: " + f);
            }
            result.add(new RestingOrder(number(f.get(0)), number(f.get(1)), side,
                    OrderType.valueOf(string(f.get(2))), TimeInForce.valueOf(string(f.get(3))), number(f.get(4)),
                    number(f.get(5)), number(f.get(6))));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object v, String what) {
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException(what + " must be an object");
        }
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object v, String what) {
        if (!(v instanceof List)) {
            throw new IllegalArgumentException(what + " must be an array");
        }
        return (List<Object>) v;
    }

    private static long number(Map<String, Object> m, String key) {
        if (!m.containsKey(key)) {
            throw new IllegalArgumentException("missing field '" + key + "'");
        }
        return number(m.get(key));
    }

    private static long number(Object v) {
        if (!(v instanceof Long l)) {
            throw new IllegalArgumentException("expected integer, got " + v);
        }
        return l;
    }

    private static String string(Object v) {
        if (!(v instanceof String str)) {
            throw new IllegalArgumentException("expected string, got " + v);
        }
        return str;
    }

    /** Minimal JSON reader for objects, arrays, ASCII strings without escapes, integers and null. */
    private static final class Reader {
        private final String s;
        private int pos;

        Reader(String s) {
            this.s = s;
        }

        Object readDocument() {
            Object v = value();
            skipWhitespace();
            if (pos != s.length()) {
                throw error("trailing characters");
            }
            return v;
        }

        private Object value() {
            char c = peek();
            if (c == '{') {
                pos++;
                Map<String, Object> m = new LinkedHashMap<>();
                if (peek() == '}') {
                    pos++;
                    return m;
                }
                while (true) {
                    String key = string();
                    expect(':');
                    if (m.containsKey(key)) {
                        throw error("duplicate key '" + key + "'");
                    }
                    m.put(key, value());
                    char n = next();
                    if (n == '}') {
                        return m;
                    }
                    if (n != ',') {
                        throw error("expected ',' or '}'");
                    }
                }
            }
            if (c == '[') {
                pos++;
                List<Object> l = new ArrayList<>();
                if (peek() == ']') {
                    pos++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    char n = next();
                    if (n == ']') {
                        return l;
                    }
                    if (n != ',') {
                        throw error("expected ',' or ']'");
                    }
                }
            }
            if (c == '"') {
                return string();
            }
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            int start = pos;
            if (c == '-') {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == start || (pos == start + 1 && c == '-')) {
                throw error("expected value");
            }
            try {
                return Long.parseLong(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("integer out of range");
            }
        }

        private String string() {
            expect('"');
            int end = s.indexOf('"', pos);
            if (end < 0) {
                throw error("unterminated string");
            }
            String v = s.substring(pos, end);
            if (v.indexOf('\\') >= 0) {
                throw error("escapes are not used in snapshots");
            }
            pos = end + 1;
            return v;
        }

        private void expect(char c) {
            if (next() != c) {
                throw error("expected '" + c + "'");
            }
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private char peek() {
            skipWhitespace();
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            return s.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + pos);
        }
    }
}

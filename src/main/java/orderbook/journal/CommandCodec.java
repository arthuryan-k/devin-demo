package orderbook.journal;

import java.util.LinkedHashMap;
import java.util.Map;

import orderbook.Command;
import orderbook.Order;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;

/**
 * Encodes a {@link Command} as a single-line flat JSON object and back. Hand-rolled to keep the project free of
 * runtime dependencies; the format only ever contains string, integer and {@code null} values.
 *
 * <pre>
 * {"cmd":"place","id":1,"participant":7,"side":"BUY","type":"LIMIT","tif":"GTC","price":100,"qty":10}
 * {"cmd":"cancel","id":1}
 * {"cmd":"amend","id":1,"price":101,"qty":null}
 * </pre>
 */
public final class CommandCodec {

    private CommandCodec() {
    }

    public static String encode(Command command) {
        if (command instanceof Command.Place place) {
            Order o = place.order();
            return "{\"cmd\":\"place\",\"id\":" + o.id() + ",\"participant\":" + o.participantId()
                    + ",\"side\":\"" + o.side() + "\",\"type\":\"" + o.type() + "\",\"tif\":\"" + o.timeInForce()
                    + "\",\"price\":" + o.price() + ",\"qty\":" + o.qtyRemaining() + "}";
        }
        if (command instanceof Command.Cancel cancel) {
            return "{\"cmd\":\"cancel\",\"id\":" + cancel.orderId() + "}";
        }
        if (command instanceof Command.Amend amend) {
            return "{\"cmd\":\"amend\",\"id\":" + amend.orderId() + ",\"price\":" + amend.newPrice()
                    + ",\"qty\":" + amend.newQty() + "}";
        }
        throw new IllegalArgumentException("unsupported command " + command);
    }

    /** @throws IllegalArgumentException if {@code line} is not a well-formed encoded command */
    public static Command decode(String line) {
        Map<String, String> f = new Parser(line).parseObject();
        String cmd = required(f, "cmd");
        long id = longField(f, "id");
        switch (cmd) {
            case "place":
                return new Command.Place(new Order(id, longField(f, "participant"),
                        Side.valueOf(required(f, "side")), OrderType.valueOf(required(f, "type")),
                        TimeInForce.valueOf(required(f, "tif")), longField(f, "price"), longField(f, "qty")));
            case "cancel":
                return new Command.Cancel(id);
            case "amend":
                return new Command.Amend(id, nullableLong(f, "price"), nullableLong(f, "qty"));
            default:
                throw new IllegalArgumentException("unknown cmd '" + cmd + "' in: " + line);
        }
    }

    private static String required(Map<String, String> f, String key) {
        String v = f.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing field '" + key + "'");
        }
        return v;
    }

    private static long longField(Map<String, String> f, String key) {
        return Long.parseLong(required(f, key));
    }

    private static Long nullableLong(Map<String, String> f, String key) {
        String v = f.get(key);
        return v == null ? null : Long.valueOf(v);
    }

    /** Parses one flat JSON object; values are returned as raw text, with JSON {@code null} mapped to Java null. */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        Map<String, String> parseObject() {
            Map<String, String> fields = new LinkedHashMap<>();
            expect('{');
            if (peek() == '}') {
                pos++;
            } else {
                while (true) {
                    String key = parseString();
                    expect(':');
                    if (fields.containsKey(key)) {
                        throw error("duplicate key '" + key + "'");
                    }
                    fields.put(key, parseValue());
                    char c = next();
                    if (c == '}') {
                        break;
                    }
                    if (c != ',') {
                        throw error("expected ',' or '}'");
                    }
                }
            }
            skipWhitespace();
            if (pos != s.length()) {
                throw error("trailing characters");
            }
            return fields;
        }

        private String parseValue() {
            char c = peek();
            if (c == '"') {
                return parseString();
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
                throw error("expected string, integer or null");
            }
            return s.substring(start, pos);
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw error("unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
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
            return new IllegalArgumentException(message + " at position " + pos + " in: " + s);
        }
    }
}

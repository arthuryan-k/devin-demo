package orderbook.journal;

import java.util.LinkedHashMap;
import java.util.Map;

import orderbook.Command;
import orderbook.Order;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.Symbols;
import orderbook.TimeInForce;

/**
 * Encodes a {@link Command} as a single-line flat JSON object and back. Hand-rolled to keep the project free of
 * runtime dependencies; the format only ever contains string, integer and {@code null} values.
 *
 * <pre>
 * {"cmd":"place","sym":"ACME","id":1,"participant":7,"side":"BUY","type":"LIMIT","tif":"GTC","price":100,"qty":10}
 * {"cmd":"cancel","sym":"ACME","id":1}
 * {"cmd":"amend","sym":"ACME","id":1,"price":101,"qty":null}
 * </pre>
 *
 * A missing {@code "sym"} decodes as {@link Symbols#DEFAULT} (entries written before the symbol dimension).
 */
public final class CommandCodec {

    private CommandCodec() {
    }

    public static String encode(Command command) {
        if (command instanceof Command.Place place) {
            Order o = place.order();
            return "{\"cmd\":\"place\"," + sym(o.symbol()) + "\"id\":" + o.id() + ",\"participant\":" + o.participantId()
                    + ",\"side\":\"" + o.side() + "\",\"type\":\"" + o.type() + "\",\"tif\":\"" + o.timeInForce()
                    + "\",\"price\":" + o.price() + ",\"qty\":" + o.qtyRemaining() + "}";
        }
        if (command instanceof Command.Cancel cancel) {
            return "{\"cmd\":\"cancel\"," + sym(cancel.symbol()) + "\"id\":" + cancel.orderId() + "}";
        }
        if (command instanceof Command.Amend amend) {
            return "{\"cmd\":\"amend\"," + sym(amend.symbol()) + "\"id\":" + amend.orderId() + ",\"price\":" + amend.newPrice()
                    + ",\"qty\":" + amend.newQty() + "}";
        }
        throw new IllegalArgumentException("unsupported command " + command);
    }

    private static String sym(String symbol) {
        if (!encodable(symbol)) {
            throw new IllegalArgumentException("unencodable symbol '" + symbol + "'");
        }
        return "\"sym\":\"" + symbol + "\",";
    }

    /** Symbols are written unescaped, so only upper-case letters, digits and dots (1-12 chars) are allowed. */
    private static boolean encodable(String symbol) {
        if (symbol.isEmpty() || symbol.length() > 12) {
            return false;
        }
        for (int i = 0; i < symbol.length(); i++) {
            char c = symbol.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.')) {
                return false;
            }
        }
        return true;
    }

    /** A command together with the global sequence number it was journaled at. */
    public record Sequenced(long seq, Command command) {
    }

    /** {@link #encode} with a leading {@code "seq"} field: {@code {"seq":12,"cmd":"cancel","id":1}}. */
    public static String encode(long seq, Command command) {
        return "{\"seq\":" + seq + "," + encode(command).substring(1);
    }

    /** Inverse of {@link #encode(long, Command)}; the {@code "seq"} field is required. */
    public static Sequenced decodeSequenced(String line) {
        Map<String, String> f = new Parser(line).parseObject();
        return new Sequenced(longField(f, "seq"), fromFields(f, line));
    }

    /** @throws IllegalArgumentException if {@code line} is not a well-formed encoded command */
    public static Command decode(String line) {
        return fromFields(new Parser(line).parseObject(), line);
    }

    private static Command fromFields(Map<String, String> f, String line) {
        String cmd = required(f, "cmd");
        String symbol = f.getOrDefault("sym", Symbols.DEFAULT);
        if (symbol == null || !encodable(symbol)) {
            throw new IllegalArgumentException("bad field 'sym' in: " + line);
        }
        long id = longField(f, "id");
        switch (cmd) {
            case "place":
                return new Command.Place(new Order(symbol, id, longField(f, "participant"),
                        Side.valueOf(required(f, "side")), OrderType.valueOf(required(f, "type")),
                        TimeInForce.valueOf(required(f, "tif")), longField(f, "price"), longField(f, "qty")));
            case "cancel":
                return new Command.Cancel(symbol, id);
            case "amend":
                return new Command.Amend(symbol, id, nullableLong(f, "price"), nullableLong(f, "qty"));
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
        try {
            return Long.parseLong(required(f, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad integer field '" + key + "'", e);
        }
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

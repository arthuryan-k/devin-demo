package orderbook.bus;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import orderbook.Command;
import orderbook.Event;
import orderbook.Order;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.Trade;

/**
 * JSON wire format of the bus (schema version {@value #VERSION}; JSON Schemas in {@code schemas/bus/}). Prices are
 * integer ticks, quantities integers. Unknown fields are rejected so that typos fail loudly instead of being ignored.
 *
 * <pre>
 * commands.&lt;symbol&gt;:
 *   {"type":"Place","participantId":7,"correlationId":"c-1","orderId":42,"side":"BUY","orderType":"LIMIT",
 *    "timeInForce":"GTC","price":10000,"qty":5}
 *   {"type":"Cancel","participantId":7,"orderId":42}
 *   {"type":"Amend","participantId":7,"orderId":42,"newPrice":10100,"newQty":null}
 * events.&lt;symbol&gt;:
 *   {"v":1,"symbol":"ACME","seq":12,"participantId":7,"correlationId":"c-1","commandOffset":31,
 *    "command":{...as above, orderId filled in...},
 *    "events":[{"type":"OrderPlaced","seq":12,...},{"type":"TradeExecuted","seq":12,...}]}
 * </pre>
 */
public final class BusCodec {

    public static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> COMMON = Set.of("type", "participantId", "correlationId", "symbol", "v");
    private static final Set<String> PLACE = Set.of("orderId", "side", "orderType", "timeInForce", "price", "qty");
    private static final Set<String> CANCEL = Set.of("orderId");
    private static final Set<String> AMEND = Set.of("orderId", "newPrice", "newQty");

    private BusCodec() {
    }

    /** A command record that is not valid JSON, or does not match the commands schema. */
    public static final class InvalidCommandException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public InvalidCommandException(String message) {
            super(message);
        }
    }

    /** One decoded {@code events.<symbol>} record. */
    public record EventsRecord(String symbol, long seq, long participantId, String correlationId, long ts,
            long commandOffset,
            Command command, List<Event> events) {
    }

    // ---- commands ---------------------------------------------------------------------------------------------------

    /**
     * Decodes a {@code commands.<symbol>} record value. A {@code symbol} field, if present, must equal
     * {@code symbol}.
     *
     * @throws InvalidCommandException if the record is malformed
     */
    public static BusCommand decodeCommand(String json, String symbol) {
        JsonNode n;
        try {
            n = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new InvalidCommandException("not JSON: " + e.getOriginalMessage());
        }
        if (n == null || !n.isObject()) {
            throw new InvalidCommandException("command must be a JSON object");
        }
        if (n.has("v") && n.get("v").asInt(-1) != VERSION) {
            throw new InvalidCommandException("unsupported version " + n.get("v"));
        }
        if (n.hasNonNull("symbol") && !symbol.equals(n.get("symbol").asText())) {
            throw new InvalidCommandException("symbol " + n.get("symbol").asText() + " on the " + symbol + " topic");
        }
        String type = text(n, "type", true);
        long participantId = integer(n, "participantId", true);
        if (participantId < 0) {
            throw new InvalidCommandException("participantId must be >= 0");
        }
        String correlationId = text(n, "correlationId", false);
        switch (type) {
            case "Place": {
                allowOnly(n, PLACE);
                Long orderId = nullableInteger(n, "orderId");
                if (orderId != null && orderId <= 0) {
                    throw new InvalidCommandException("orderId must be > 0");
                }
                Side side = enumValue(Side.class, text(n, "side", true));
                String typeText = text(n, "orderType", false);
                OrderType orderType = typeText == null ? OrderType.LIMIT : enumValue(OrderType.class, typeText);
                String tifText = text(n, "timeInForce", false);
                TimeInForce tif = tifText != null ? enumValue(TimeInForce.class, tifText)
                        : orderType == OrderType.MARKET ? TimeInForce.IOC : TimeInForce.GTC;
                long price = orderType == OrderType.MARKET ? 0 : integer(n, "price", true);
                long qty = integer(n, "qty", true);
                Order order = new Order(symbol, orderId == null ? 0 : orderId, participantId, side, orderType, tif, price, qty);
                return new BusCommand(correlationId, participantId, new Command.Place(order), orderId == null);
            }
            case "Cancel":
                allowOnly(n, CANCEL);
                return new BusCommand(correlationId, participantId, new Command.Cancel(symbol, integer(n, "orderId", true)),
                        false);
            case "Amend":
                allowOnly(n, AMEND);
                return new BusCommand(correlationId, participantId, new Command.Amend(symbol, integer(n, "orderId", true),
                        nullableInteger(n, "newPrice"), nullableInteger(n, "newQty")), false);
            default:
                throw new InvalidCommandException("unknown type '" + type + "'");
        }
    }

    /** Encodes a command in the {@code commands.<symbol>} format (also echoed inside events records). */
    public static String encodeCommand(String symbol, long participantId, String correlationId, Command command) {
        return commandNode(symbol, participantId, correlationId, command).toString();
    }

    private static ObjectNode commandNode(String symbol, long participantId, String correlationId, Command command) {
        ObjectNode n = MAPPER.createObjectNode();
        if (command instanceof Command.Place place) {
            Order o = place.order();
            n.put("type", "Place");
            common(n, symbol, participantId, correlationId);
            n.put("orderId", o.id());
            n.put("side", o.side().name());
            n.put("orderType", o.type().name());
            n.put("timeInForce", o.timeInForce().name());
            n.put("price", o.price());
            n.put("qty", o.qtyRemaining());
        } else if (command instanceof Command.Cancel cancel) {
            n.put("type", "Cancel");
            common(n, symbol, participantId, correlationId);
            n.put("orderId", cancel.orderId());
        } else {
            Command.Amend amend = (Command.Amend) command;
            n.put("type", "Amend");
            common(n, symbol, participantId, correlationId);
            n.put("orderId", amend.orderId());
            putNullable(n, "newPrice", amend.newPrice());
            putNullable(n, "newQty", amend.newQty());
        }
        return n;
    }

    private static void common(ObjectNode n, String symbol, long participantId, String correlationId) {
        if (symbol != null) {
            n.put("symbol", symbol);
        }
        n.put("participantId", participantId);
        if (correlationId != null) {
            n.put("correlationId", correlationId);
        }
    }

    // ---- events -----------------------------------------------------------------------------------------------------

    /** Owner and side of a live order, for the maker/taker fields of {@code TradeExecuted}. */
    public interface Parties {
        long participantOf(long orderId);

        Side sideOf(long orderId);
    }

    public static String encodeEvents(String symbol, long seq, long participantId, String correlationId,
            long commandOffset, Command command, List<Event> events, Parties parties, long timestampMillis) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("v", VERSION);
        n.put("symbol", symbol);
        n.put("seq", seq);
        n.put("participantId", participantId);
        n.put("correlationId", correlationId);
        n.put("ts", timestampMillis);
        n.put("commandOffset", commandOffset);
        ObjectNode cmd = commandNode(null, participantId, null, command);
        cmd.remove("participantId");
        n.set("command", cmd);
        ArrayNode arr = n.putArray("events");
        for (Event e : events) {
            arr.add(eventNode(seq, e, command, parties));
        }
        return n.toString();
    }

    private static ObjectNode eventNode(long seq, Event event, Command command, Parties parties) {
        ObjectNode n = MAPPER.createObjectNode();
        if (event instanceof Event.OrderPlaced e) {
            Order o = ((Command.Place) command).order();
            n.put("type", "OrderPlaced").put("seq", seq).put("orderId", e.orderId()).put("side", e.side().name())
                    .put("orderType", o.type().name()).put("timeInForce", o.timeInForce().name())
                    .put("price", e.price()).put("qty", e.qty()).put("seqNum", e.seqNum());
        } else if (event instanceof Event.TradeExecuted e) {
            Trade t = e.trade();
            n.put("type", "TradeExecuted").put("seq", seq).put("makerOrderId", t.makerOrderId())
                    .put("takerOrderId", t.takerOrderId())
                    .put("makerParticipantId", parties.participantOf(t.makerOrderId()))
                    .put("takerParticipantId", parties.participantOf(t.takerOrderId()))
                    .put("takerSide", parties.sideOf(t.takerOrderId()).name())
                    .put("price", t.price()).put("qty", t.qty());
        } else if (event instanceof Event.OrderCancelled e) {
            n.put("type", "OrderCancelled").put("seq", seq).put("orderId", e.orderId())
                    .put("cancelledQty", e.cancelledQty()).put("reason", e.reason().name());
        } else if (event instanceof Event.OrderAmended e) {
            n.put("type", "OrderAmended").put("seq", seq).put("orderId", e.orderId()).put("oldPrice", e.oldPrice())
                    .put("newPrice", e.newPrice()).put("oldQty", e.oldQty()).put("newQty", e.newQty())
                    .put("seqNum", e.seqNum()).put("priorityRetained", e.priorityRetained());
        } else {
            Event.OrderRejected e = (Event.OrderRejected) event;
            n.put("type", "OrderRejected").put("seq", seq).put("orderId", e.orderId())
                    .put("reason", e.reason().name());
        }
        return n;
    }

    /** Inverse of {@link #encodeEvents}. */
    public static EventsRecord decodeEvents(String json) {
        JsonNode n;
        try {
            n = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("not JSON: " + e.getOriginalMessage(), e);
        }
        long participantId = n.get("participantId").asLong();
        String symbol = n.get("symbol").asText();
        ObjectNode cmd = ((ObjectNode) n.get("command")).deepCopy();
        cmd.put("participantId", participantId);
        Command command = decodeCommand(cmd.toString(), symbol).command();
        List<Event> events = new ArrayList<>();
        for (JsonNode e : n.get("events")) {
            events.add(decodeEvent(e));
        }
        JsonNode corr = n.get("correlationId");
        return new EventsRecord(symbol, n.get("seq").asLong(), participantId,
                corr == null || corr.isNull() ? null : corr.asText(), n.get("ts").asLong(),
                n.get("commandOffset").asLong(), command, events);
    }

    private static Event decodeEvent(JsonNode e) {
        String type = e.get("type").asText();
        switch (type) {
            case "OrderPlaced":
                return new Event.OrderPlaced(e.get("orderId").asLong(), Side.valueOf(e.get("side").asText()),
                        e.get("price").asLong(), e.get("qty").asLong(), e.get("seqNum").asLong());
            case "TradeExecuted":
                return new Event.TradeExecuted(new Trade(e.get("makerOrderId").asLong(),
                        e.get("takerOrderId").asLong(), e.get("price").asLong(), e.get("qty").asLong()));
            case "OrderCancelled":
                return new Event.OrderCancelled(e.get("orderId").asLong(), e.get("cancelledQty").asLong(),
                        Event.OrderCancelled.CancelReason.valueOf(e.get("reason").asText()));
            case "OrderAmended":
                return new Event.OrderAmended(e.get("orderId").asLong(), e.get("oldPrice").asLong(),
                        e.get("newPrice").asLong(), e.get("oldQty").asLong(), e.get("newQty").asLong(),
                        e.get("seqNum").asLong(), e.get("priorityRetained").asBoolean());
            case "OrderRejected":
                return new Event.OrderRejected(e.get("orderId").asLong(),
                        Event.OrderRejected.Reason.valueOf(e.get("reason").asText()));
            default:
                throw new IllegalArgumentException("unknown event type " + type);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static void allowOnly(JsonNode n, Set<String> specific) {
        for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext(); ) {
            String key = it.next().getKey();
            if (!COMMON.contains(key) && !specific.contains(key)) {
                throw new InvalidCommandException("unknown field '" + key + "'");
            }
        }
    }

    private static String text(JsonNode n, String field, boolean required) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            if (required) {
                throw new InvalidCommandException("missing field '" + field + "'");
            }
            return null;
        }
        if (!v.isTextual()) {
            throw new InvalidCommandException("field '" + field + "' must be a string");
        }
        return v.asText();
    }

    private static long integer(JsonNode n, String field, boolean required) {
        Long v = nullableInteger(n, field);
        if (v == null) {
            throw new InvalidCommandException("missing field '" + field + "'");
        }
        return v;
    }

    private static Long nullableInteger(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (!v.isIntegralNumber() || !v.canConvertToLong()) {
            throw new InvalidCommandException("field '" + field + "' must be an integer");
        }
        return v.asLong();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String text) {
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw new InvalidCommandException("bad " + type.getSimpleName() + " '" + text + "'");
        }
    }

    private static void putNullable(ObjectNode n, String field, Long value) {
        if (value == null) {
            n.putNull(field);
        } else {
            n.put(field, value);
        }
    }
}

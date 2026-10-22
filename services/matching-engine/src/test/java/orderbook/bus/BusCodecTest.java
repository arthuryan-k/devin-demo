package orderbook.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import orderbook.Command;
import orderbook.Event;
import orderbook.Order;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.Trade;

class BusCodecTest {

    private static final BusCodec.Parties PARTIES = new BusCodec.Parties() {
        @Override
        public long participantOf(long orderId) {
            return orderId == 1 ? 11 : 22;
        }

        @Override
        public Side sideOf(long orderId) {
            return orderId == 1 ? Side.SELL : Side.BUY;
        }
    };

    @Test
    void decodesPlaceWithDefaultsAndOptionalCorrelationId() {
        BusCommand c = BusCodec.decodeCommand(
                "{\"type\":\"Place\",\"participantId\":7,\"side\":\"BUY\",\"price\":10000,\"qty\":5}", "ACME");
        assertNull(c.correlationId());
        assertTrue(c.needsOrderId());
        Order o = ((Command.Place) c.command()).order();
        assertEquals("ACME", o.symbol());
        assertEquals(7, o.participantId());
        assertEquals(OrderType.LIMIT, o.type());
        assertEquals(TimeInForce.GTC, o.timeInForce());

        BusCommand m = BusCodec.decodeCommand("{\"type\":\"Place\",\"participantId\":7,\"correlationId\":\"x\","
                + "\"orderId\":9,\"side\":\"SELL\",\"orderType\":\"MARKET\",\"qty\":5}", "ACME");
        assertEquals("x", m.correlationId());
        assertFalse(m.needsOrderId());
        assertEquals(TimeInForce.IOC, ((Command.Place) m.command()).order().timeInForce());
        assertEquals(13, m.withOrderId(13).command() instanceof Command.Place p ? p.order().id() : -1);
    }

    @Test
    void decodesCancelAndAmend() {
        assertEquals(new Command.Cancel("GLOBX", 4),
                BusCodec.decodeCommand("{\"type\":\"Cancel\",\"participantId\":1,\"orderId\":4}", "GLOBX").command());
        assertEquals(new Command.Amend("GLOBX", 4, null, 3L), BusCodec.decodeCommand(
                "{\"type\":\"Amend\",\"participantId\":1,\"orderId\":4,\"newPrice\":null,\"newQty\":3}", "GLOBX")
                .command());
    }

    @Test
    void rejectsMalformedCommands() {
        for (String bad : List.of("nope", "[]", "{\"type\":\"Cancel\",\"participantId\":1}",
                "{\"type\":\"Cancel\",\"participantId\":1,\"orderId\":4,\"price\":3}",
                "{\"type\":\"Place\",\"participantId\":1,\"side\":\"UP\",\"price\":1,\"qty\":1}",
                "{\"type\":\"Place\",\"participantId\":1,\"side\":\"BUY\",\"price\":1.5,\"qty\":1}",
                "{\"type\":\"Cancel\",\"participantId\":1,\"orderId\":4,\"symbol\":\"NVTA\"}",
                "{\"type\":\"Cancel\",\"participantId\":1,\"orderId\":4,\"v\":2}",
                "{\"type\":\"Halt\",\"participantId\":1}")) {
            assertThrows(BusCodec.InvalidCommandException.class, () -> BusCodec.decodeCommand(bad, "ACME"), bad);
        }
    }

    @Test
    void eventsRecordRoundTripsAndCarriesTradeParties() throws Exception {
        Command place = new Command.Place(new Order("ACME", 2, 22, Side.BUY, OrderType.LIMIT, TimeInForce.IOC, 100, 4));
        List<Event> events = List.of(new Event.OrderPlaced(2, Side.BUY, 100, 4, 3),
                new Event.TradeExecuted(new Trade(1, 2, 100, 3)),
                new Event.OrderCancelled(2, 1, Event.OrderCancelled.CancelReason.UNFILLED_REMAINDER));
        String json = BusCodec.encodeEvents("ACME", 41, 22, null, 77, place, events, PARTIES, 1234);

        JsonNode n = new ObjectMapper().readTree(json);
        assertTrue(n.get("correlationId").isNull());
        assertEquals(1234, n.get("ts").asLong());
        JsonNode trade = n.get("events").get(1);
        assertEquals(11, trade.get("makerParticipantId").asLong());
        assertEquals(22, trade.get("takerParticipantId").asLong());
        assertEquals("BUY", trade.get("takerSide").asText());
        assertEquals("IOC", n.get("events").get(0).get("timeInForce").asText());
        for (JsonNode e : n.get("events")) {
            assertEquals(41, e.get("seq").asLong());
        }

        BusCodec.EventsRecord r = BusCodec.decodeEvents(json);
        assertEquals(new BusCodec.EventsRecord("ACME", 41, 22, null, 1234, 77, place, events), r);
    }
}

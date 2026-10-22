package fixgw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** events.<symbol> envelopes (schemas/bus/events.schema.json) -> per-participant EngineEvents. */
class KafkaOrderBusTranslateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static List<EngineEvent> translate(String json) throws Exception {
        return KafkaOrderBus.translate(JSON.readTree(json));
    }

    @Test
    void placeThatTradesAgainstARestingOrder() throws Exception {
        List<EngineEvent> out = translate("{\"v\":1,\"symbol\":\"ACME\",\"seq\":7,\"participantId\":202,"
                + "\"correlationId\":\"c9\",\"ts\":1,\"commandOffset\":7,\"command\":{\"type\":\"Place\",\"orderId\":12},"
                + "\"events\":[{\"type\":\"OrderPlaced\",\"seq\":7,\"orderId\":12,\"side\":\"BUY\",\"orderType\":\"LIMIT\","
                + "\"timeInForce\":\"GTC\",\"price\":10000,\"qty\":4,\"seqNum\":7},"
                + "{\"type\":\"TradeExecuted\",\"seq\":7,\"makerOrderId\":3,\"makerParticipantId\":101,"
                + "\"takerOrderId\":12,\"takerParticipantId\":202,\"takerSide\":\"BUY\",\"price\":9990,\"qty\":4}]}");
        assertEquals(3, out.size());
        assertEquals(EngineEvent.ACCEPTED, out.get(0).type);
        assertEquals(12, out.get(0).orderId);
        assertEquals("c9", out.get(0).clOrdId);
        EngineEvent maker = out.get(1);
        assertEquals(EngineEvent.FILL, maker.type);
        assertEquals(101, maker.participantId);
        assertEquals(3, maker.orderId);
        assertNull(maker.clOrdId);
        EngineEvent taker = out.get(2);
        assertEquals(202, taker.participantId);
        assertEquals(12, taker.orderId);
        assertEquals(9990, taker.price);
        assertEquals(4, taker.qty);
    }

    @Test
    void cancelAmendRejectAndIocRemainder() throws Exception {
        assertEquals(EngineEvent.CANCELED, translate("{\"symbol\":\"ACME\",\"participantId\":1,\"correlationId\":\"x\","
                + "\"command\":{\"type\":\"Cancel\",\"orderId\":5},"
                + "\"events\":[{\"type\":\"OrderCancelled\",\"orderId\":5,\"cancelledQty\":3,\"reason\":\"USER\"}]}")
                .get(0).type);
        assertEquals(EngineEvent.EXPIRED, translate("{\"symbol\":\"ACME\",\"participantId\":1,\"correlationId\":\"x\","
                + "\"command\":{\"type\":\"Place\",\"orderId\":6},"
                + "\"events\":[{\"type\":\"OrderCancelled\",\"orderId\":6,\"cancelledQty\":3,\"reason\":\"IOC\"}]}")
                .get(0).type);
        EngineEvent amended = translate("{\"symbol\":\"ACME\",\"participantId\":1,\"correlationId\":\"x\","
                + "\"command\":{\"type\":\"Amend\",\"orderId\":5},\"events\":[{\"type\":\"OrderAmended\",\"orderId\":5,"
                + "\"oldPrice\":100,\"newPrice\":105,\"oldQty\":3,\"newQty\":2,\"priorityRetained\":false}]}").get(0);
        assertEquals(EngineEvent.REPLACED, amended.type);
        assertEquals(105, amended.price);
        assertEquals(2, amended.leavesQty);
        EngineEvent rej = translate("{\"symbol\":\"ACME\",\"participantId\":1,\"correlationId\":null,"
                + "\"command\":{\"type\":\"Cancel\",\"orderId\":99},"
                + "\"events\":[{\"type\":\"OrderRejected\",\"orderId\":99,\"reason\":\"UNKNOWN_ORDER\"}]}").get(0);
        assertEquals(EngineEvent.REJECTED, rej.type);
        assertEquals("UNKNOWN_ORDER", rej.text);
        assertNull(rej.clOrdId);
    }
}

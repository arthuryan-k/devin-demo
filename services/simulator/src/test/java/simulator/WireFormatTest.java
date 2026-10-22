package simulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Gateway order replies and market-data feed messages, in the formats the Phase 5 services emit. */
class WireFormatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ExchangeClient.Reply reply(String json, long known) throws Exception {
        return GatewayExchangeClient.interpret(JSON.readTree(json), known);
    }

    @Test
    void gatewayRepliesFoldIntoFilledAndRestingQty() throws Exception {
        ExchangeClient.Reply r = reply("{\"orderId\":77,\"symbol\":\"ACME\",\"seq\":3,\"events\":["
                + "{\"type\":\"OrderPlaced\",\"orderId\":77,\"side\":\"BUY\",\"price\":\"100.25\",\"qty\":10},"
                + "{\"type\":\"TradeExecuted\",\"price\":\"100.20\",\"qty\":4,\"makerOrderId\":5,\"takerOrderId\":77}]}",
                -1);
        assertEquals(new ExchangeClient.Reply(200, 77, 4, 6, null), r);

        ExchangeClient.Reply ioc = reply("{\"orderId\":78,\"events\":[{\"type\":\"OrderPlaced\",\"orderId\":78,\"qty\":10},"
                + "{\"type\":\"TradeExecuted\",\"qty\":3,\"makerOrderId\":5,\"takerOrderId\":78},"
                + "{\"type\":\"OrderCancelled\",\"orderId\":78,\"cancelledQty\":7,\"reason\":\"IOC\"}]}", -1);
        assertEquals(3, ioc.filledQty());
        assertEquals(0, ioc.restingQty());

        assertEquals(0, reply("{\"orderId\":5,\"events\":[{\"type\":\"OrderCancelled\",\"orderId\":5,"
                + "\"cancelledQty\":2,\"reason\":\"USER\"}]}", 5).restingQty());
        assertEquals(8, reply("{\"orderId\":5,\"events\":[{\"type\":\"OrderAmended\",\"orderId\":5,"
                + "\"newPrice\":\"99.00\",\"newQty\":8}]}", 5).restingQty());

        ExchangeClient.Reply risk = reply("{\"orderId\":79,\"events\":[{\"type\":\"RiskRejected\","
                + "\"reason\":\"NO_SHORTING\",\"message\":\"x\"}]}", -1);
        assertFalse(risk.ok());
        assertEquals("NO_SHORTING", risk.reason());
        assertEquals(404, reply("{\"events\":[{\"type\":\"OrderRejected\",\"orderId\":9,\"reason\":\"UNKNOWN_ORDER\"}]}",
                9).status());
        assertEquals("100.25", GatewayExchangeClient.decimal(10025));
        assertEquals("0.05", GatewayExchangeClient.decimal(5));
    }

    @Test
    void feedKeepsAReplicaAndReportsTradesAndGaps() throws Exception {
        try (MarketDataFeed feed = new MarketDataFeed("http://x", HttpClient.newHttpClient(), List.of("ACME"))) {
            List<MarketView.Trade> trades = new ArrayList<>();
            feed.addTradeListener(trades::add);
            assertTrue(feed.apply(JSON.readTree("{\"type\":\"snapshot\",\"symbol\":\"ACME\",\"seq\":41,"
                    + "\"bids\":[[10025,300,2],[10000,50,1]],\"asks\":[[10050,100,1]],\"trades\":[],\"events\":[]}")));
            assertTrue(feed.apply(JSON.readTree("{\"type\":\"delta\",\"symbol\":\"ACME\",\"seq\":42,"
                    + "\"levels\":[[\"u\",\"B\",10025,200,1],[\"r\",\"S\",10050,0,0],[\"a\",\"S\",10075,10,1]],"
                    + "\"events\":[{\"type\":\"TradeExecuted\",\"price\":10050,\"qty\":100,\"makerOrderId\":3,"
                    + "\"takerOrderId\":9,\"takerSide\":\"BUY\"}]}")));
            assertEquals(List.of(new Level(10025, 200, 1), new Level(10000, 50, 1)), feed.depth("ACME", Side.BUY, 5));
            assertEquals(List.of(new Level(10075, 10, 1)), feed.depth("ACME", Side.SELL, 5));
            assertEquals(List.of(new MarketView.Trade("ACME", 3, 9, 10050, 100)), trades);
            assertTrue(feed.apply(JSON.readTree("{\"type\":\"delta\",\"symbol\":\"ACME\",\"seq\":42,\"levels\":[]}")));
            assertFalse(feed.apply(JSON.readTree("{\"type\":\"delta\",\"symbol\":\"ACME\",\"seq\":44,\"levels\":[]}")));
        }
    }

    @Test
    void parsesSymbolSpecsAndLabels() {
        assertEquals(List.of(new MarketSimulator.SymbolSpec("ACME", 10000), new MarketSimulator.SymbolSpec("NVTA", 20050)),
                Main.parseSymbols("ACME:100.00, NVTA:200.50"));
        assertEquals("ACME", PortfolioFunder.symbolOf("MM_ACME_3-1007"));
    }
}

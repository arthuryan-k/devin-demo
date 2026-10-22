package orderbook.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import orderbook.Symbols;
import orderbook.api.AdmissionControl;
import orderbook.api.ParticipantRegistry;
import orderbook.services.Instrument;
import orderbook.sim.Simulator;

class SymbolsEndpointTest {

    @Test
    void servesTheConfiguredListingAndTheTradedSymbol() {
        List<Instrument> listing = List.of(
                new Instrument("ACME", "Acme Corporation", "USD", new BigDecimal("0.01"), 1, new BigDecimal("2500.00")),
                new Instrument("NVTA", "Novata \"Rx\"", "USD", new BigDecimal("0.005"), 100, BigDecimal.ZERO));
        DemoApp app = new DemoApp(new Simulator(1), AdmissionControl.Limits.DEFAULT,
                new ParticipantRegistry(new java.util.Random(1)), listing);
        try {
            DemoApp.Reply reply = app.handle("GET", "/api/symbols", null, "", null);
            assertEquals(200, reply.status());
            assertEquals("{\"traded\":\"ACME\",\"symbols\":["
                    + "{\"symbol\":\"ACME\",\"name\":\"Acme Corporation\",\"currency\":\"USD\",\"tickSize\":\"0.01\",\"lotSize\":1},"
                    + "{\"symbol\":\"NVTA\",\"name\":\"Novata \\\"Rx\\\"\",\"currency\":\"USD\",\"tickSize\":\"0.005\",\"lotSize\":100}]}",
                    reply.json());
            assertEquals(405, app.handle("POST", "/api/symbols", null, "", null).status());
        } finally {
            app.close();
        }
    }

    @Test
    void withoutReferenceDataTheBuiltInListingIsServed() {
        DemoApp app = new DemoApp(new Simulator(1));
        try {
            String json = app.handle("GET", "/api/symbols", null, "", null).json();
            for (String symbol : Symbols.ALL) {
                assertTrue(json.contains("\"symbol\":\"" + symbol + "\""), json);
            }
        } finally {
            app.close();
        }
    }
}

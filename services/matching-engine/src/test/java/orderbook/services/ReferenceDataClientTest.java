package orderbook.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class ReferenceDataClientTest {

    private static final String LISTING = """
            {"symbols":[
              {"symbol":"ACME","name":"Acme Corporation","currency":"USD","tickSize":"0.01","lotSize":1,"initialCashGrant":"2500.00","active":true},
              {"symbol":"OLD","name":"Delisted \\"Co\\"","currency":"USD","tickSize":"0.05","lotSize":10,"initialCashGrant":"0","active":false},
              {"symbol":"NVTA","name":"Novata Therapeutics","currency":"USD","tickSize":"0.005","lotSize":100,"initialCashGrant":"1000.5","active":true}
            ]}""";

    @Test
    void readsActiveInstrumentsInListingOrderWithExactDecimals() throws Exception {
        try (FakeService refdata = new FakeService().on("GET /symbols", s -> new FakeService.Reply(200, LISTING))) {
            List<Instrument> symbols = new ReferenceDataClient(URI.create(refdata.uri() + "/")).symbols();
            assertEquals(List.of(
                    new Instrument("ACME", "Acme Corporation", "USD", new BigDecimal("0.01"), 1, new BigDecimal("2500.00")),
                    new Instrument("NVTA", "Novata Therapeutics", "USD", new BigDecimal("0.005"), 100,
                            new BigDecimal("1000.5"))), symbols);
        }
    }

    @Test
    void failuresAreIoExceptionsAfterTheRetries() throws Exception {
        try (FakeService refdata = new FakeService()
                .on("GET /symbols", s -> new FakeService.Reply(503, "{\"error\":\"starting\"}"))) {
            ReferenceDataClient client = new ReferenceDataClient(refdata.uri());
            assertThrows(IOException.class, () -> client.symbols(3, Duration.ofMillis(10)));
            assertEquals(3, refdata.count("/symbols"));
        }
        try (FakeService refdata = new FakeService().on("GET /symbols", s -> new FakeService.Reply(200, "[]"))) {
            assertThrows(IOException.class, () -> new ReferenceDataClient(refdata.uri()).symbols());
        }
    }
}

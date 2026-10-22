package orderbook.services;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads the listed instruments from the reference-data service ({@code GET /symbols}). */
public final class ReferenceDataClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final URI base;
    private final HttpClient http;

    public ReferenceDataClient(URI base) {
        this(base, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    ReferenceDataClient(URI base, HttpClient http) {
        this.base = URI.create(base.toString().replaceAll("/+$", "") + "/");
        this.http = http;
    }

    /** Active instruments in listing order. @throws IOException if the service is unreachable or answers badly */
    public List<Instrument> symbols() throws IOException {
        HttpResponse<String> res;
        try {
            res = http.send(HttpRequest.newBuilder(base.resolve("symbols")).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (res.statusCode() != 200) {
            throw new IOException("GET " + base.resolve("symbols") + " returned HTTP " + res.statusCode());
        }
        try {
            return parse(res.body());
        } catch (RuntimeException e) {
            throw new IOException("bad reference-data response: " + e.getMessage(), e);
        }
    }

    /** {@link #symbols()} with retries while the service starts (compose brings it up alongside the engine). */
    public List<Instrument> symbols(int attempts, Duration backoff) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return symbols();
            } catch (IOException e) {
                if (attempt >= attempts) {
                    throw e;
                }
                try {
                    Thread.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", ie);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    static List<Instrument> parse(String json) {
        Object symbols = JsonReader.object(json).get("symbols");
        if (!(symbols instanceof List<?> list)) {
            throw new IllegalArgumentException("expected {\"symbols\":[...]}");
        }
        List<Instrument> out = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (Boolean.FALSE.equals(m.get("active"))) {
                continue;
            }
            out.add(new Instrument((String) m.get("symbol"), (String) m.get("name"), (String) m.get("currency"),
                    new BigDecimal(String.valueOf(m.get("tickSize"))),
                    ((BigDecimal) m.get("lotSize")).intValueExact(),
                    new BigDecimal(String.valueOf(m.get("initialCashGrant")))));
        }
        return List.copyOf(out);
    }
}

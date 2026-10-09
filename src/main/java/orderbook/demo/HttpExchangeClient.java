package orderbook.demo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.sim.ExchangeClient;

/** {@link ExchangeClient} over the public HTTP API: what a simulated participant looks like to the server. */
final class HttpExchangeClient implements ExchangeClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern PARTICIPANT_ID = Pattern.compile("\"participantId\":(\\d+)");
    private static final Pattern LABEL = Pattern.compile("\"label\":\"([^\"]*)\"");
    private static final Pattern API_KEY = Pattern.compile("\"apiKey\":\"([^\"]+)\"");
    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\":(-?\\d+)");
    private static final Pattern REASON = Pattern.compile("\"reason\":\"([^\"]*)\"");

    private final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    HttpExchangeClient(URI base) {
        this.base = base;
    }

    @Override
    public Credentials register(String name) {
        HttpResponse<String> response = send("POST", "/participants/register", null, form(Map.of("name", name)));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("registration failed: HTTP " + response.statusCode());
        }
        String body = response.body();
        return new Credentials(Long.parseLong(group(PARTICIPANT_ID, body, "-1")), group(LABEL, body, ""),
                group(API_KEY, body, ""));
    }

    @Override
    public Reply place(String apiKey, Side side, OrderType type, long price, long qty, TimeInForce timeInForce) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("side", side.name());
        fields.put("type", type.name());
        if (type == OrderType.LIMIT) {
            fields.put("price", Ticks.format(price));
        }
        fields.put("qty", Long.toString(qty));
        fields.put("timeInForce", timeInForce.name());
        return reply(send("POST", "/api/orders", apiKey, form(fields)));
    }

    @Override
    public Reply cancel(String apiKey, long orderId) {
        return reply(send("DELETE", "/api/orders/" + orderId, apiKey, ""));
    }

    @Override
    public Reply amend(String apiKey, long orderId, Long newPrice, Long newQty) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (newPrice != null) {
            fields.put("price", Ticks.format(newPrice));
        }
        if (newQty != null) {
            fields.put("qty", Long.toString(newQty));
        }
        return reply(send("PATCH", "/api/orders/" + orderId, apiKey, form(fields)));
    }

    private HttpResponse<String> send(String method, String path, String apiKey, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null) {
            request.header(DemoServer.API_KEY_HEADER, apiKey);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static Reply reply(HttpResponse<String> response) {
        String body = response.body();
        return new Reply(response.statusCode(), Long.parseLong(group(ORDER_ID, body, "-1")), group(REASON, body, null));
    }

    private static String form(Map<String, String> fields) {
        StringBuilder out = new StringBuilder();
        fields.forEach((k, v) -> {
            if (v != null) {
                out.append(out.length() == 0 ? "" : "&").append(k).append('=')
                        .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        });
        return out.toString();
    }

    private static String group(Pattern pattern, String text, String fallback) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : fallback;
    }
}

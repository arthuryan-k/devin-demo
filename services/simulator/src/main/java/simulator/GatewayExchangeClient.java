package simulator;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The simulator's way into the exchange, identical to any outside bot: sign up through auth-service (via the
 * gateway's {@code POST /participants/register} proxy), get funded, then trade over the gateway's public REST API with
 * the account's own {@code X-Api-Key}. Prices cross the wire as decimals (tick 0.01).
 */
public final class GatewayExchangeClient implements ExchangeClient {

    /** Grants a new account its starting inventory, before its first order. */
    @FunctionalInterface
    public interface Funder {
        void fund(Credentials account);

        Funder NONE = account -> {
        };
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JSON_TYPE = "application/json";

    private final String gateway;
    private final AuthClient auth;
    private final Funder funder;
    private final HttpClient http;

    public GatewayExchangeClient(String gatewayUrl, HttpClient http, Funder funder) {
        this.gateway = gatewayUrl.replaceAll("/+$", "");
        this.http = http;
        this.auth = new AuthClient(gateway, http);
        this.funder = funder;
    }

    static String decimal(long ticks) {
        return BigDecimal.valueOf(ticks, 2).toPlainString();
    }

    static long ticks(JsonNode price) {
        return price.isNumber() && price.isIntegralNumber() && !price.isTextual() ? price.asLong() * 100
                : new BigDecimal(price.asText()).movePointRight(2).longValueExact();
    }

    @Override
    public Credentials register(String name) {
        Credentials c = auth.register(name);
        funder.fund(c);
        return c;
    }

    @Override
    public Reply place(String apiKey, String symbol, Side side, OrderType type, long price, long qty,
            TimeInForce timeInForce) {
        ObjectNode body = JSON.createObjectNode();
        body.put("symbol", symbol);
        body.put("side", side.name());
        body.put("type", type.name());
        if (type == OrderType.LIMIT) {
            body.put("price", decimal(price));
        }
        body.put("qty", qty);
        body.put("timeInForce", timeInForce.name());
        return send(HttpRequest.newBuilder(URI.create(gateway + "/api/orders")).header("Content-Type", JSON_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())), apiKey, -1);
    }

    @Override
    public Reply cancel(String apiKey, String symbol, long orderId) {
        return send(HttpRequest.newBuilder(URI.create(gateway + "/api/orders/" + orderId + "?symbol=" + symbol))
                .DELETE(), apiKey, orderId);
    }

    @Override
    public Reply amend(String apiKey, String symbol, long orderId, Long newPrice, Long newQty) {
        ObjectNode body = JSON.createObjectNode();
        body.put("symbol", symbol);
        if (newPrice != null) {
            body.put("price", decimal(newPrice));
        }
        if (newQty != null) {
            body.put("qty", newQty);
        }
        return send(HttpRequest.newBuilder(URI.create(gateway + "/api/orders/" + orderId + "?symbol=" + symbol))
                .header("Content-Type", JSON_TYPE).method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString())), apiKey, orderId);
    }

    private Reply send(HttpRequest.Builder b, String apiKey, long orderId) {
        // no Content-Type on bodiless DELETEs: the gateway rejects an empty JSON body with 400
        HttpRequest req = b.timeout(Duration.ofSeconds(10)).header("X-Api-Key", apiKey).build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            return Reply.failed(599, "gateway unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.failed(599, "interrupted");
        }
        JsonNode node;
        try {
            node = JSON.readTree(res.body());
        } catch (IOException e) {
            return Reply.failed(res.statusCode() >= 300 ? res.statusCode() : 502, "unparseable reply");
        }
        if (res.statusCode() != 200) {
            return Reply.failed(res.statusCode(), node.path("reason").asText(node.path("error").asText("")));
        }
        return interpret(node, orderId);
    }

    /**
     * Folds the engine events of one command into a {@link Reply}. Risk and engine rejections come back as HTTP 200
     * with a {@code RiskRejected}/{@code OrderRejected} event; they map to 422 (and 404 for an order that's gone).
     */
    static Reply interpret(JsonNode node, long knownOrderId) {
        long orderId = node.path("orderId").asLong(knownOrderId);
        long placed = 0;
        long filled = 0;
        long cancelled = 0;
        Long amendedQty = null;
        boolean cancelledByRequest = false;
        for (JsonNode e : iterable(node.path("events"))) {
            String type = e.path("type").asText();
            switch (type) {
                case "RiskRejected" -> {
                    String reason = e.path("reason").asText("RISK_REJECTED");
                    return Reply.failed("NOT_OWN_OPEN_ORDER".equals(reason) ? 404 : 422, reason);
                }
                case "OrderRejected" -> {
                    String reason = e.path("reason").asText("REJECTED");
                    return Reply.failed(reason.contains("UNKNOWN") ? 404 : 422, reason);
                }
                case "OrderPlaced" -> {
                    orderId = e.path("orderId").asLong(orderId);
                    placed = e.path("qty").asLong();
                }
                case "TradeExecuted" -> {
                    if (e.path("takerOrderId").asLong() == orderId) {
                        filled += e.path("qty").asLong();
                    }
                }
                case "OrderCancelled" -> {
                    if (e.path("orderId").asLong() == orderId) {
                        cancelled += e.path("cancelledQty").asLong();
                        cancelledByRequest = knownOrderId > 0;
                    }
                }
                case "OrderAmended" -> amendedQty = e.path("newQty").asLong();
                default -> {
                }
            }
        }
        long resting;
        if (amendedQty != null) {
            resting = amendedQty;
        } else if (cancelledByRequest) {
            resting = 0;
        } else {
            resting = Math.max(0, placed - filled - cancelled);
        }
        return new Reply(200, orderId, filled, resting, null);
    }

    private static Iterable<JsonNode> iterable(JsonNode array) {
        return array.isArray() ? array::elements : List.<JsonNode>of();
    }
}

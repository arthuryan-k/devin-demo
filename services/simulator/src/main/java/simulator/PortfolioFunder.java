package simulator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Opens each new simulated account in the portfolio ledger with its starting inventory ({@code POST /v1/accounts}):
 * the auth-service cash grant plus some shares of the symbol it trades, at the symbol's starting reference price.
 * The exchange forbids shorting, so a fresh cash-only population could never sell; this is the endowment a real
 * participant would arrive with. 409 (already open) is fine.
 */
public final class PortfolioFunder implements GatewayExchangeClient.Funder {

    private static final Logger LOG = Logger.getLogger(PortfolioFunder.class.getName());

    private final URI accounts;
    private final HttpClient http;
    private final Function<String, String> symbolOfLabel;
    private final Map<String, Long> startTicks;
    private final long shares;

    /** {@code symbolOfLabel} maps an account label (e.g. {@code MM_ACME_3-1007}) to its symbol. */
    public PortfolioFunder(String portfolioUrl, HttpClient http, Function<String, String> symbolOfLabel,
            Map<String, Long> startTicks, long shares) {
        this.accounts = URI.create(portfolioUrl.replaceAll("/+$", "") + "/v1/accounts");
        this.http = http;
        this.symbolOfLabel = symbolOfLabel;
        this.startTicks = startTicks;
        this.shares = shares;
    }

    @Override
    public void fund(ExchangeClient.Credentials account) {
        String symbol = symbolOfLabel.apply(account.label());
        Long avgCost = symbol == null ? null : startTicks.get(symbol);
        String positions = avgCost == null || shares <= 0 ? "[]"
                : "[{\"symbol\":\"" + symbol + "\",\"shares\":" + shares + ",\"avgCost\":" + avgCost + "}]";
        String body = "{\"accountId\":" + account.participantId() + ",\"positions\":" + positions + "}";
        HttpRequest req = HttpRequest.newBuilder(accounts).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 201 && res.statusCode() != 409) {
                throw new IllegalStateException("funding " + account.label() + " failed: HTTP " + res.statusCode()
                        + " " + res.body());
            }
        } catch (IOException e) {
            throw new IllegalStateException("portfolio unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        LOG.fine(() -> "funded " + account.label());
    }

    /** {@code MM_ACME_3-1007} -> {@code ACME}. */
    public static String symbolOf(String label) {
        String[] parts = label.split("[_-]");
        return parts.length >= 2 ? parts[1] : null;
    }
}

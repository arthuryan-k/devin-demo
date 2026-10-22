package simulator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signs a simulated participant up for its own account via auth-service {@code POST /participants/register}
 * ({@code {name}} -> {@code 201 {participantId, label, apiKey, ...}}): a real user of kind {@code simulated} with its
 * own account, cash grant and API key.
 */
public final class AuthClient {

    private static final Pattern PID = Pattern.compile("\"participantId\"\\s*:\\s*(\\d+)");
    private static final Pattern LABEL = Pattern.compile("\"label\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern KEY = Pattern.compile("\"apiKey\"\\s*:\\s*\"([^\"]*)\"");

    private final URI register;
    private final HttpClient http;

    public AuthClient(String baseUrl, HttpClient http) {
        this.register = URI.create(baseUrl.replaceAll("/+$", "") + "/participants/register");
        this.http = http;
    }

    public ExchangeClient.Credentials register(String name) {
        HttpRequest req = HttpRequest.newBuilder(register).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":" + AdminServer.str(name) + "}")).build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("auth-service unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        if (res.statusCode() != 201) {
            throw new IllegalStateException("sign-up of " + name + " failed: HTTP " + res.statusCode() + " "
                    + res.body());
        }
        return new ExchangeClient.Credentials(Long.parseLong(group(PID, res.body())), group(LABEL, res.body()),
                group(KEY, res.body()));
    }

    private static String group(Pattern p, String body) {
        Matcher m = p.matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("unexpected sign-up response: " + body);
        }
        return m.group(1);
    }
}

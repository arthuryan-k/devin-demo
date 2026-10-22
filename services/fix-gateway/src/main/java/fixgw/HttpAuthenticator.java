package fixgw;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies the Logon Password (554) as an API key against auth-service's internal route
 * {@code GET /internal/api-keys/verify} (header {@code X-Api-Key}). Username (553) is only logged.
 * Caches good keys for a minute because the old box this replaced did the same.
 */
public class HttpAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(HttpAuthenticator.class.getName());
    private static final Pattern PID = Pattern.compile("\"participantId\"\\s*:\\s*(\\d+)");
    private static final long TTL_MS = 60000;

    private final String baseUrl;
    private final String internalToken;
    private final Map cache = new HashMap(); // String key -> long[] {pid, expiresAt}

    public HttpAuthenticator(String baseUrl, String internalToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.internalToken = internalToken;
    }

    public long authenticate(String username, String password) {
        if (password == null || password.trim().length() == 0) return -1;
        synchronized (cache) {
            long[] hit = (long[]) cache.get(password);
            if (hit != null && hit[1] > System.currentTimeMillis()) return hit[0];
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/internal/api-keys/verify").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            c.setRequestProperty("X-Api-Key", password);
            if (internalToken != null && internalToken.length() > 0) {
                c.setRequestProperty("X-Internal-Token", internalToken);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                LOG.info("api key refused for " + username + ": HTTP " + code);
                return -1;
            }
            String body = read(c.getInputStream());
            Matcher m = PID.matcher(body);
            if (!m.find()) return -1;
            long pid = Long.parseLong(m.group(1));
            synchronized (cache) {
                cache.put(password, new long[] {pid, System.currentTimeMillis() + TTL_MS});
            }
            return pid;
        } catch (IOException e) {
            LOG.warning("auth-service unreachable: " + e);
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        StringBuffer sb = new StringBuffer();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        in.close();
        return sb.toString();
    }
}

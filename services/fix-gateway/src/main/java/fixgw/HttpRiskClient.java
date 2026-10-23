package fixgw;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** POSTs /v1/orders/check, /check-amend, /check-cancel on the risk service. 5xx or no answer = rejected. */
public class HttpRiskClient implements RiskClient {

    private static final Logger LOG = Logger.getLogger(HttpRiskClient.class.getName());
    private static final Pattern APPROVED = Pattern.compile("\"approved\"\\s*:\\s*(true|false)");
    private static final Pattern REASON = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern LIMIT = Pattern.compile("\"limitPrice\"\\s*:\\s*(\\d+)");

    private final String baseUrl;

    public HttpRiskClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // TODO: switch to java.net.http.HttpClient (keep-alive, async) - not available on Java 8.
    public Decision check(OrderCommand c) {
        String path;
        StringBuffer body = new StringBuffer();
        body.append("{\"participantId\":").append(c.participantId).append(",\"symbol\":\"").append(c.symbol)
                .append("\",\"orderId\":").append(c.orderId);
        if (OrderCommand.NEW.equals(c.type)) {
            path = "/v1/orders/check";
            body.append(",\"side\":\"").append(c.side).append("\",\"orderType\":\"").append(c.ordType)
                    .append("\",\"timeInForce\":\"").append(c.tif).append("\",\"price\":")
                    .append("LIMIT".equals(c.ordType) ? String.valueOf(c.price) : "null").append(",\"qty\":")
                    .append(c.qty);
        } else if (OrderCommand.REPLACE.equals(c.type)) {
            path = "/v1/orders/check-amend";
            body.append(",\"newPrice\":").append(c.price).append(",\"newQty\":").append(c.qty);
        } else {
            path = "/v1/orders/check-cancel";
        }
        body.append('}');
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = conn.getResponseCode();
            if (code != 200) return Decision.no(code >= 500 ? "RISK_UNAVAILABLE" : "RISK_REJECTED");
            String reply = read(conn.getInputStream());
            Matcher a = APPROVED.matcher(reply);
            if (a.find() && "true".equals(a.group(1))) {
                Decision d = Decision.ok();
                Matcher l = LIMIT.matcher(reply);
                if (l.find()) d.limitPrice = Long.parseLong(l.group(1));
                return d;
            }
            Matcher r = REASON.matcher(reply);
            return Decision.no(r.find() ? r.group(1) : "RISK_REJECTED");
        } catch (Exception e) {
            LOG.warning("risk unreachable: " + e);
            return Decision.no("RISK_UNAVAILABLE");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        StringBuffer sb = new StringBuffer();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        in.close();
        return sb.toString();
    }
}

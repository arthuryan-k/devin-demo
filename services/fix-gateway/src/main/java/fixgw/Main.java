package fixgw;

import java.util.Arrays;

/** FIX_PORT, AUTH_URL, RISK_URL ("none" to skip), GATEWAY_INSTANCE, INTERNAL_TOKEN, KAFKA_BOOTSTRAP, SYMBOLS, FIX_COMP_ID. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("FIX_PORT", "9878"));
        String symbols = env("SYMBOLS", "ACME,GLOBX,INITD,NVTA");
        Authenticator auth = new HttpAuthenticator(env("AUTH_URL", "http://auth-service:8081"),
                env("INTERNAL_TOKEN", ""));
        KafkaOrderBus bus = new KafkaOrderBus(env("KAFKA_BOOTSTRAP", "redpanda:9092"),
                Arrays.asList(symbols.split(",")));
        String riskUrl = env("RISK_URL", "http://risk:8092");
        RiskClient risk = "none".equals(riskUrl) ? RiskClient.ALLOW_ALL : new HttpRiskClient(riskUrl);
        FixServer server = new FixServer(auth, bus, risk, new OrderIds(Integer.parseInt(env("GATEWAY_INSTANCE", "15"))),
                env("FIX_COMP_ID", "FIXGW"));
        server.start(port);
        Thread.currentThread().join();
    }

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.length() == 0 ? d : v;
    }
}

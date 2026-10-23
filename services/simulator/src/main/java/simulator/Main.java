package simulator;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The simulator container. Env: GATEWAY_URL, PORTFOLIO_URL, SYMBOLS ({@code ACME:100.00,...}), SEED, RATE,
 * PARTICIPANTS (per symbol), ENDOWMENT_SHARES, ADMIN_PORT, AUTOSTART.
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String gateway = env("GATEWAY_URL", "http://gateway:8080");
        List<MarketSimulator.SymbolSpec> symbols = parseSymbols(env("SYMBOLS",
                "ACME:100.00,GLOBX:50.00,INITD:25.00,NVTA:200.00"));
        Map<String, Long> start = new LinkedHashMap<>();
        symbols.forEach(s -> start.put(s.symbol(), s.startTicks()));
        // HTTP/1.1: the JDK client's default h2c "Upgrade" header trips the gateway's proxy (it reads it as a WebSocket upgrade)
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).build();

        String portfolio = env("PORTFOLIO_URL", "");
        GatewayExchangeClient.Funder funder = portfolio.isEmpty() ? GatewayExchangeClient.Funder.NONE
                : new PortfolioFunder(portfolio, http, PortfolioFunder::symbolOf, start,
                        Long.parseLong(env("ENDOWMENT_SHARES", "500")));
        GatewayExchangeClient exchange = new GatewayExchangeClient(gateway, http, funder);
        MarketDataFeed feed = new MarketDataFeed(gateway, http, new ArrayList<>(start.keySet())).connect();

        MarketSimulator sim = new MarketSimulator(Long.parseLong(env("SEED", "42")), symbols, exchange, feed,
                ClientLoop.dedicatedThreads(), true);
        sim.setRate(Double.parseDouble(env("RATE", String.valueOf(MarketSimulator.DEFAULT_RATE))));
        sim.setTargetParticipants(Integer.parseInt(env("PARTICIPANTS",
                String.valueOf(MarketSimulator.DEFAULT_PARTICIPANTS))));
        AdminServer admin = new AdminServer(sim, Integer.parseInt(env("ADMIN_PORT", "8095")));
        int port = admin.start();
        LOG.info("simulator admin on :" + port + ", trading via " + gateway);
        if (Boolean.parseBoolean(env("AUTOSTART", "true"))) {
            sim.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sim.close();
            feed.close();
            admin.close();
        }));
        Thread.currentThread().join();
    }

    static List<MarketSimulator.SymbolSpec> parseSymbols(String spec) {
        List<MarketSimulator.SymbolSpec> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) {
                throw new IllegalArgumentException("SYMBOLS entries are SYM:price, got '" + part + "'");
            }
            out.add(new MarketSimulator.SymbolSpec(kv[0], new java.math.BigDecimal(kv[1]).movePointRight(2)
                    .longValueExact()));
        }
        return out;
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}

package simulator;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Admin/control endpoint for demos: {@code GET /admin/status}, {@code POST /admin/start}, {@code /admin/stop},
 * {@code /admin/rate?value=}, {@code /admin/participants?value=}, {@code /admin/reset?seed=}. Every POST returns
 * the new status. CORS is open so the web UI can drive it directly.
 */
public final class AdminServer implements AutoCloseable {

    private final MarketSimulator sim;
    private final HttpServer server;

    public AdminServer(MarketSimulator sim, int port) throws IOException {
        this.sim = sim;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/admin/", this::handle);
        server.createContext("/healthz", ex -> send(ex, 200, "{\"ok\":true}"));
        server.setExecutor(Executors.newFixedThreadPool(2));
    }

    public int start() {
        server.start();
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) {
                send(ex, 204, null);
                return;
            }
            String path = ex.getRequestURI().getPath().substring("/admin/".length());
            Map<String, String> q = query(ex.getRequestURI());
            if (path.equals("status") && "GET".equals(method)) {
                send(ex, 200, json(sim.status()));
                return;
            }
            if (!"POST".equals(method)) {
                send(ex, 405, error("use POST"));
                return;
            }
            switch (path) {
                case "start" -> sim.start();
                case "stop" -> sim.stop();
                case "rate" -> sim.setRate(Double.parseDouble(required(q, "value")));
                case "participants" -> sim.setTargetParticipants(Integer.parseInt(required(q, "value")));
                case "reset" -> sim.reset(Long.parseLong(required(q, "seed")));
                default -> {
                    send(ex, 404, error("unknown endpoint " + path));
                    return;
                }
            }
            send(ex, 200, json(sim.status()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, error(e.getMessage()));
        } catch (RuntimeException e) {
            send(ex, 500, error(String.valueOf(e)));
        }
    }

    private static String required(Map<String, String> q, String name) {
        String v = q.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing query parameter '" + name + "'");
        }
        return v;
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                out.put(java.net.URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String error(String message) {
        return "{\"error\":" + str(message) + "}";
    }

    static String json(MarketSimulator.Status s) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"running\":").append(s.running());
        b.append(",\"seed\":").append(s.seed());
        b.append(",\"rate\":").append(s.rate());
        b.append(",\"targetParticipants\":").append(s.targetParticipants());
        b.append(",\"simTime\":").append(s.simTime());
        b.append(",\"arrivals\":").append(s.arrivals());
        b.append(",\"accountsCreated\":").append(s.accountsCreated());
        b.append(",\"replies\":{");
        boolean first = true;
        for (Map.Entry<Integer, Long> e : s.replies().entrySet()) {
            b.append(first ? "" : ",").append('"').append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        b.append("},\"symbols\":[");
        for (int i = 0; i < s.symbols().size(); i++) {
            MarketSimulator.SymbolStatus sym = s.symbols().get(i);
            b.append(i == 0 ? "" : ",").append("{\"symbol\":").append(str(sym.symbol()));
            b.append(",\"referenceTicks\":").append(sym.referenceTicks());
            b.append(",\"shockIntensity\":").append(sym.shockIntensity());
            b.append(",\"participants\":[");
            for (int j = 0; j < sym.participants().size(); j++) {
                MarketSimulator.ParticipantInfo p = sym.participants().get(j);
                b.append(j == 0 ? "" : ",").append("{\"label\":").append(str(p.label()));
                b.append(",\"persona\":").append(str(p.persona().name()));
                b.append(",\"participantId\":").append(p.participantId());
                b.append(",\"restingOrders\":").append(p.restingOrders()).append('}');
            }
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    static String str(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}

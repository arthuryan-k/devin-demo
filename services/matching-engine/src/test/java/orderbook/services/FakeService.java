package orderbook.services;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A canned HTTP service on loopback: {@code "METHOD /path"} → (status, body); records every request. */
final class FakeService implements AutoCloseable {

    record Reply(int status, String body) {
    }

    record Seen(String method, String path, Map<String, String> headers, String body) {
    }

    private final HttpServer server;
    private final Map<String, Function<Seen, Reply>> routes = new ConcurrentHashMap<>();
    final List<Seen> seen = new CopyOnWriteArrayList<>();

    FakeService() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    FakeService on(String methodAndPath, Function<Seen, Reply> handler) {
        routes.put(methodAndPath, handler);
        return this;
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    long count(String path) {
        return seen.stream().filter(s -> s.path().equals(path)).count();
    }

    private void handle(HttpExchange ex) throws IOException {
        Map<String, String> headers = new ConcurrentHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        Seen s = new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers,
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        seen.add(s);
        Function<Seen, Reply> route = routes.get(s.method() + " " + s.path());
        Reply r = route == null ? new Reply(404, "{\"error\":\"not_found\"}") : route.apply(s);
        byte[] body = r.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(r.status(), body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

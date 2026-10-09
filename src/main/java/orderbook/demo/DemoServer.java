package orderbook.demo;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import orderbook.sim.Simulator;

/** Serves {@link DemoApp} on embedded Jetty: the page, the JSON API and the {@code /ws} stream on one port. */
public final class DemoServer {

    static final long USER = Simulator.USER_PARTICIPANT_ID;
    private static final Duration WS_IDLE_TIMEOUT = Duration.ofSeconds(60);

    private final Server server;
    private final ServerConnector connector;
    private final DemoApp app;

    public DemoServer(InetSocketAddress address) throws IOException {
        this(address, new Simulator(defaultSeed()), null);
    }

    /** {@code accountFactory} builds the user's account at startup and on reset; null for a random starting cash. */
    public DemoServer(InetSocketAddress address, Simulator simulator, Supplier<Account> accountFactory)
            throws IOException {
        app = new DemoApp(simulator, accountFactory);
        server = new Server();
        connector = new ServerConnector(server);
        connector.setHost(address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString());
        connector.setPort(address.getPort());
        server.addConnector(connector);
        WebSocketUpgradeHandler ws = WebSocketUpgradeHandler.from(server, container -> {
            container.setIdleTimeout(WS_IDLE_TIMEOUT);
            container.addMapping("/ws", (req, res, cb) -> new StreamSocket(sinceParam(req.getHttpURI().getQuery())));
        });
        ws.setHandler(new Routes());
        server.setHandler(ws);
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        DemoServer demo = new DemoServer(new InetSocketAddress(port));
        demo.start();
        System.out.println("Order book demo running at http://localhost:" + demo.port() + "/ (simulator seed "
                + demo.simulator().seed() + ")");
    }

    /** {@code -Dsim.seed=N} or {@code SIM_SEED=N} for a reproducible simulation; random otherwise. */
    private static long defaultSeed() {
        String text = System.getProperty("sim.seed", System.getenv("SIM_SEED"));
        return text != null && !text.isBlank() ? Long.parseLong(text.trim()) : new SecureRandom().nextLong();
    }

    public void start() throws IOException {
        try {
            server.start();
        } catch (Exception e) {
            throw new IOException("failed to start server", e);
        }
        app.start();
    }

    /** Stops the HTTP server (closing WebSocket sessions), then the stream and the simulator. */
    public void stop() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("failed to stop server", e);
        } finally {
            app.close();
        }
    }

    public int port() {
        return connector.getLocalPort();
    }

    Simulator simulator() {
        return app.simulator();
    }

    private final class Routes extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String path = request.getHttpURI().getPath();
            if (!app.isApi(path)) {
                serveStatic(path, response, callback);
                return true;
            }
            DemoApp.Reply reply = app.handle(request.getMethod(), path, request.getHttpURI().getQuery(),
                    Content.Source.asString(request, StandardCharsets.UTF_8));
            send(response, callback, reply.status(), "application/json", reply.json().getBytes(StandardCharsets.UTF_8));
            return true;
        }
    }

    private static OptionalLong sinceParam(String query) {
        String text = DemoApp.parseForm(query).get("since");
        if (text == null || text.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(text.trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** One browser connection; inbound text (keep-alive pings) is ignored. Public because Jetty invokes it reflectively. */
    public final class StreamSocket implements Session.Listener.AutoDemanding {
        private final OptionalLong since;
        private volatile Outbox outbox;

        StreamSocket(OptionalLong since) {
            this.since = since;
        }

        @Override
        public void onWebSocketOpen(Session session) {
            outbox = app.connect(new Outbox.Transport() {
                @Override
                public void send(String text, Runnable onSuccess, Consumer<Throwable> onFailure) {
                    session.sendText(text, org.eclipse.jetty.websocket.api.Callback.from(onSuccess, onFailure));
                }

                @Override
                public void close() {
                    session.close();
                }
            }, since);
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason,
                org.eclipse.jetty.websocket.api.Callback callback) {
            disconnect();
            callback.succeed();
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            disconnect();
        }

        private void disconnect() {
            Outbox out = outbox;
            if (out != null) {
                app.disconnect(out);
            }
        }
    }

    private void serveStatic(String path, Response response, Callback callback) throws IOException {
        if (!path.equals("/") && !path.equals("/index.html")) {
            send(response, callback, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = DemoServer.class.getResourceAsStream("/demo/index.html")) {
            if (in == null) {
                send(response, callback, 500, "text/plain", "index.html missing".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(response, callback, 200, "text/html; charset=utf-8", in.readAllBytes());
        }
    }

    private static void send(Response response, Callback callback, int status, String contentType, byte[] body) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.write(true, ByteBuffer.wrap(body), callback);
    }
}

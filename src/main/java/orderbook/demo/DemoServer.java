package orderbook.demo;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

import orderbook.journal.Journal;
import orderbook.marketdata.Outbox;
import orderbook.pipeline.JournalHandler;
import orderbook.sim.Simulator;

/** Serves {@link DemoApp} on embedded Jetty: the page, the JSON API and the SSE market-data stream on one port. */
public final class DemoServer {

    static final long USER = Simulator.USER_PARTICIPANT_ID;
    static final String STREAM_PATH = "/marketdata/stream";
    private static final long HEARTBEAT_SECONDS = 15;

    private final Server server;
    private final ServerConnector connector;
    private final DemoApp app;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

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
        server.setHandler(new Routes());
        attachJournal(simulator, System.getProperty("journal.path", System.getenv("JOURNAL_PATH")));
    }

    /** {@code -Djournal.path=FILE} or {@code JOURNAL_PATH}: adds the journal writer to the output ring. */
    private static void attachJournal(Simulator simulator, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        Journal journal = new Journal(Path.of(path.trim()));
        simulator.pipeline().addConsumer(new JournalHandler(journal, () -> {
            try {
                journal.close();
                Files.deleteIfExists(journal.path());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }));
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
        heartbeat.scheduleAtFixedRate(app.marketData()::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
    }

    /** Stops the HTTP server, then the stream clients, the simulator and its pipeline. */
    public void stop() {
        try {
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("failed to stop server", e);
        } finally {
            heartbeat.shutdownNow();
            app.close();
        }
    }

    public int port() {
        return connector.getLocalPort();
    }

    Simulator simulator() {
        return app.simulator();
    }

    orderbook.marketdata.MarketDataPublisher marketData() {
        return app.marketData();
    }

    private final class Routes extends Handler.Abstract {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception {
            String path = request.getHttpURI().getPath();
            if (path.equals(STREAM_PATH)) {
                if (!request.getMethod().equals("GET")) {
                    send(response, callback, 405, "application/json", "{\"error\":\"use GET\"}".getBytes(StandardCharsets.UTF_8));
                } else {
                    stream(response, callback);
                }
                return true;
            }
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

    /**
     * {@code GET /marketdata/stream}: Server-Sent Events, one {@code data:} line per market-data message. The first
     * message is always a snapshot; empty frames become {@code :} keepalive comments. Each (re)connect starts with
     * a fresh snapshot, so {@code Last-Event-ID} is not needed.
     */
    private void stream(Response response, Callback callback) {
        response.setStatus(200);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.getHeaders().put("X-Accel-Buffering", "no");
        AtomicBoolean done = new AtomicBoolean();
        Outbox[] subscription = new Outbox[1];
        Outbox.Transport transport = new Outbox.Transport() {
            @Override
            public void send(String text, Runnable onSuccess, Consumer<Throwable> onFailure) {
                String frame = text.isEmpty() ? ":\n\n" : "data: " + text + "\n\n";
                response.write(false, ByteBuffer.wrap(frame.getBytes(StandardCharsets.UTF_8)),
                        Callback.from(onSuccess, onFailure));
            }

            @Override
            public void close() {
                if (done.compareAndSet(false, true)) {
                    Outbox out = subscription[0];
                    if (out != null) {
                        app.disconnect(out);
                    }
                    callback.succeeded();
                }
            }
        };
        synchronized (subscription) {
            subscription[0] = app.connect(transport);
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

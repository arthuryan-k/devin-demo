package orderbook.demo;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

import orderbook.Symbols;
import orderbook.exchange.ExchangeCore;
import orderbook.journal.Journal;
import orderbook.marketdata.Outbox;
import orderbook.pipeline.JournalHandler;
import orderbook.pipeline.Pipeline;
import orderbook.api.AdmissionControl;
import orderbook.api.ParticipantDirectory;
import orderbook.api.ParticipantRegistry;
import orderbook.services.AuthServiceDirectory;
import orderbook.services.Instrument;
import orderbook.services.ReferenceDataClient;
import orderbook.sim.ClientLoop;
import orderbook.sim.ExecutorScheduler;
import orderbook.sim.Simulator;

/** Serves {@link DemoApp} on embedded Jetty: the page, the JSON API and the SSE market-data stream on one port. */
public final class DemoServer {

    static final long USER = Simulator.USER_PARTICIPANT_ID;
    static final String STREAM_PATH = "/marketdata/stream";
    static final String API_KEY_HEADER = "X-Api-Key";
    static final String SESSION_COOKIE = "ob_session";
    private static final long HEARTBEAT_SECONDS = 15;

    private final Server server;
    private final ServerConnector connector;
    private final DemoApp app;
    /** Signed-in browsers carry the auth service's JWT in {@value #SESSION_COOKIE}; never overwrite it. */
    private final boolean authService;
    /** Owned exchange core (one engine per symbol) when started from {@link #main}; null if given a simulator. */
    private final ExchangeCore core;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    /**
     * Hosts an {@link ExchangeCore} with every listed symbol; the simulator and the browser trade
     * {@link Symbols#DEFAULT}, the other books start (and stay) empty until external flow arrives.
     */
    public DemoServer(InetSocketAddress address) throws IOException {
        this(address, Backends.fromEnv());
    }

    private DemoServer(InetSocketAddress address, Backends backends) throws IOException {
        this(address, backends, createCore(System.getProperty("journal.path", System.getenv("JOURNAL_PATH")),
                backends.instruments()));
    }

    private DemoServer(InetSocketAddress address, Backends backends, ExchangeCore core) throws IOException {
        this(address, new Simulator(defaultSeed(), Simulator.DEFAULT_START_REFERENCE_TICKS,
                new ExecutorScheduler("simulator"), core.engine(Symbols.DEFAULT).gateway()),
                AdmissionControl.Limits.DEFAULT, core, backends);
    }

    /**
     * Where identities and the listing come from. {@code AUTH_SERVICE_URL} (+ {@code AUTH_INTERNAL_TOKEN}) switches
     * API keys, registration and session tokens to the auth service; {@code REFDATA_URL} hosts the symbols the
     * reference-data service lists. Unset, both stay in-process (in-memory keys, {@link Symbols#ALL}).
     */
    record Backends(ParticipantDirectory directory, List<Instrument> instruments, boolean authService) {

        static Backends local() {
            return new Backends(new ParticipantRegistry(new SecureRandom()), DemoApp.builtInInstruments(), false);
        }

        static Backends fromEnv() throws IOException {
            Backends local = local();
            String auth = setting("auth.url", "AUTH_SERVICE_URL");
            String refdata = setting("refdata.url", "REFDATA_URL");
            ParticipantDirectory directory = auth == null ? local.directory()
                    : new AuthServiceDirectory(URI.create(auth), setting("auth.internalToken", "AUTH_INTERNAL_TOKEN"),
                            Duration.ofSeconds(Long.getLong("auth.cacheSeconds", 30)));
            List<Instrument> instruments = local.instruments();
            if (refdata != null) {
                instruments = new ReferenceDataClient(URI.create(refdata)).symbols(30, Duration.ofSeconds(1));
                if (instruments.stream().noneMatch(i -> i.symbol().equals(Symbols.DEFAULT))) {
                    throw new IOException("reference data at " + refdata + " does not list " + Symbols.DEFAULT
                            + ", which the simulator and the page trade");
                }
            }
            return new Backends(directory, instruments, auth != null);
        }

        private static String setting(String property, String env) {
            String v = System.getProperty(property, System.getenv(env));
            return v == null || v.isBlank() ? null : v.trim();
        }
    }

    public DemoServer(InetSocketAddress address, Simulator simulator) throws IOException {
        this(address, simulator, AdmissionControl.Limits.DEFAULT);
    }

    /** {@code limits} are the per-participant admission limits (rate, burst, open orders, violations). */
    public DemoServer(InetSocketAddress address, Simulator simulator, AdmissionControl.Limits limits) throws IOException {
        this(address, simulator, limits, null);
    }

    private DemoServer(InetSocketAddress address, Simulator simulator, AdmissionControl.Limits limits, ExchangeCore core) throws IOException {
        this(address, simulator, limits, core, Backends.local());
    }

    DemoServer(InetSocketAddress address, Simulator simulator, AdmissionControl.Limits limits, ExchangeCore core, Backends backends) throws IOException {
        this.core = core;
        this.authService = backends.authService();
        app = new DemoApp(simulator, limits, backends.directory(), backends.instruments());
        server = new Server();
        connector = new ServerConnector(server);
        connector.setHost(address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString());
        connector.setPort(address.getPort());
        server.addConnector(connector);
        server.setHandler(new Routes());
        if (core == null) {
            attachJournal(simulator, System.getProperty("journal.path", System.getenv("JOURNAL_PATH")));
        }
    }

    /**
     * {@code -Djournal.path=DIR} or {@code JOURNAL_PATH}: journal root; each symbol's pipeline journals into
     * {@code DIR/<SYMBOL>/}. Options:
     * {@code -Djournal.durability=FLUSH|FSYNC}, {@code -Djournal.fsyncEvery=N} commands (default 64),
     * {@code -Djournal.fsyncMillis=T} (default 10) and {@code -Djournal.snapshotEvery=N} commands (default 10000,
     * 0 disables).
     */
    private static ExchangeCore createCore(String path, List<Instrument> instruments) {
        ExchangeCore.Builder builder = ExchangeCore.builder().symbols(instruments.stream().map(Instrument::symbol).toList());
        if (path != null && !path.isBlank()) {
            builder.journalRoot(Path.of(path.trim())).journalOptions(journalOptions())
                    .snapshotInterval(snapshotInterval());
        }
        return builder.build();
    }

    private static void attachJournal(Simulator simulator, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        Pipeline pipeline = simulator.pipeline();
        Journal journal = new Journal(ExchangeCore.journalDirectory(Path.of(path.trim()), pipeline.symbol()),
                journalOptions());
        pipeline.addConsumer(new JournalHandler(journal));
        pipeline.setSnapshotInterval(snapshotInterval());
    }

    private static Journal.Options journalOptions() {
        return "FSYNC".equalsIgnoreCase(System.getProperty("journal.durability", "FLUSH"))
                ? Journal.Options.fsync(Integer.getInteger("journal.fsyncEvery", 64),
                        Long.getLong("journal.fsyncMillis", 10))
                : Journal.Options.FLUSH;
    }

    private static int snapshotInterval() {
        return Integer.getInteger("journal.snapshotEvery", 10_000);
    }

    /** The exchange core this server owns, or null if it was given a simulator. */
    ExchangeCore core() {
        return core;
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
        app.simulator().connect(new HttpExchangeClient(baseUri()), ClientLoop.dedicatedThreads());
        heartbeat.scheduleAtFixedRate(app.marketData()::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * Stops the simulation first (its clients leave over HTTP), then the HTTP server, the stream clients, the
     * simulator and its pipeline.
     */
    public void stop() {
        try {
            app.simulator().stop();
            server.stop();
        } catch (Exception e) {
            throw new IllegalStateException("failed to stop server", e);
        } finally {
            heartbeat.shutdownNow();
            app.close();
            if (core != null) {
                core.close();
            }
        }
    }

    public int port() {
        return connector.getLocalPort();
    }

    /** Where this server's own API clients (the simulated participants) connect. */
    URI baseUri() {
        String host = connector.getHost();
        if (host == null || host.equals("0.0.0.0") || host.equals("::")) {
            host = "127.0.0.1";
        } else if (host.contains(":")) {
            host = "[" + host + "]";
        }
        return URI.create("http://" + host + ":" + port());
    }

    /** The browser session's reserved API key, also set as the {@value #SESSION_COOKIE} cookie by {@code GET /}. */
    String userKey() {
        return app.userKey();
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
                serveStatic(path, apiKey(request), response, callback);
                return true;
            }
            DemoApp.Reply reply = app.handle(request.getMethod(), path, request.getHttpURI().getQuery(),
                    Content.Source.asString(request, StandardCharsets.UTF_8), apiKey(request));
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

    /** {@value #API_KEY_HEADER} for API clients; the browser page authenticates with its session cookie instead. */
    private static String apiKey(Request request) {
        String header = request.getHeaders().get(API_KEY_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        for (String cookies : request.getHeaders().getValuesList(HttpHeader.COOKIE)) {
            for (String cookie : cookies.split(";")) {
                int eq = cookie.indexOf('=');
                if (eq > 0 && cookie.substring(0, eq).trim().equals(SESSION_COOKIE)) {
                    return cookie.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    private static final byte[] LANDING_PAGE = ("<!doctype html><title>Order Book Demo</title>"
            + "<p>Order Book Demo matching engine API. The trading UI is the React app in <code>services/web</code>"
            + " (<code>npm run dev</code>).</p>").getBytes(StandardCharsets.UTF_8);

    /** {@code /} sets the browser session cookie (participant YOU) unless the caller already holds an auth-service JWT. */
    private void serveStatic(String path, String session, Response response, Callback callback) {
        if (!path.equals("/") && !path.equals("/index.html")) {
            send(response, callback, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!(authService && session != null && AuthServiceDirectory.isJwt(session))) {
            response.getHeaders().put(HttpHeader.SET_COOKIE,
                    SESSION_COOKIE + "=" + app.userKey() + "; Path=/; HttpOnly; SameSite=Strict");
        }
        send(response, callback, 200, "text/html; charset=utf-8", LANDING_PAGE);
    }

    private static void send(Response response, Callback callback, int status, String contentType, byte[] body) {
        response.setStatus(status);
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, contentType);
        response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-store");
        response.write(true, ByteBuffer.wrap(body), callback);
    }
}

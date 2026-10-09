package orderbook.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import orderbook.Command;
import orderbook.Event;
import orderbook.MatchingEngine;
import orderbook.Order;
import orderbook.OrderBook;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.pipeline.DisruptorPipeline;
import orderbook.pipeline.Gateway;
import orderbook.pipeline.Pipeline;
import orderbook.pipeline.Result;
import orderbook.pipeline.ResultEvent;

/**
 * Market simulation around one {@link MatchingEngine}. A weighted mix of {@link Persona}s trades around a hidden
 * {@link ReferencePrice}; participants come and go (churn), more so after volatility shocks.
 *
 * <p>Threading: the engine and all simulation state are confined to one single-threaded executor. Every command,
 * simulated or external ({@link #submit}), is processed there, and {@link #execute} runs arbitrary reads or
 * check-then-act sequences there atomically. The engine itself is untouched: the simulator only issues
 * {@link Command}s through {@link MatchingEngine#process}.
 *
 * <p>Order arrivals are a Poisson process: inter-arrival delays are exponential with mean {@code 1 / rate}. The same
 * delays also drive simulated time, so with a given seed (and no external commands) the sequence of commands is
 * reproducible regardless of wall-clock timing. Simulated participants never use {@link #USER_PARTICIPANT_ID}.
 */
public final class Simulator implements AutoCloseable {

    public static final long USER_PARTICIPANT_ID = 1;
    public static final String USER_LABEL = "YOU";
    public static final long DEFAULT_START_REFERENCE_TICKS = 100_00;
    public static final double DEFAULT_RATE = 5;
    public static final double MAX_RATE = 100;
    private static final long EXIT_TIMEOUT_MILLIS = 5_000;
    private static final int SNAPSHOT_LEVELS = 10;
    private static final long RESULT_TIMEOUT_MILLIS = 10_000;

    static final int MIN_PARTICIPANTS = 2;
    static final int MAX_PARTICIPANTS = 8;
    static final int INITIAL_PARTICIPANTS = 5;
    /** Below this many participants, newcomers arrive faster. */
    static final int TARGET_PARTICIPANTS = 4;
    static final double BASE_EXIT_PER_SEC = 0.004;
    static final double SHOCK_EXIT_MULTIPLIER = 40;
    static final double ENTRY_PER_SEC = 0.04;
    static final double REFILL_ENTRY_PER_SEC = 0.3;
    static final double ENTRY_RAMP_SEC = 8;

    private static final int MAX_TRACKED_ORDERS = 500_000;
    private static final Logger LOG = Logger.getLogger(Simulator.class.getName());

    /** Notified on the simulator thread after every processed command, simulated or external. */
    @FunctionalInterface
    public interface Listener {
        void onCommand(long participantId, Command command, List<Event> events);
    }

    /** Public view of a participant; deliberately omits hidden traits such as risk tolerance. */
    public record ParticipantInfo(long id, String label, Persona.Kind kind) {
    }

    private record OrderOwner(long participantId, Side side) {
    }

    private final long seed;
    private final long startReferenceTicks;
    private final Random random;
    private final Scheduler scheduler;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile double rate = DEFAULT_RATE;

    private final Pipeline pipeline;
    private final Gateway gateway;
    private final boolean ownsPipeline;
    private final Inbox inbox = new Inbox();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();

    // Confined to the executor thread. The engine is a read-model replica, rebuilt from the output ring in sequence
    // order; matching itself happens on the pipeline's engine thread.
    private MatchingEngine engine = new MatchingEngine();
    private long applied = -1;
    private Result lastApplied;
    private ReferencePrice reference;
    private final List<Participant> participants = new ArrayList<>();
    private final Map<Long, String> labels = new ConcurrentHashMap<>();
    private final Map<Long, OrderOwner> owners = new LinkedHashMap<>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, OrderOwner> eldest) {
            return size() > MAX_TRACKED_ORDERS;
        }
    };
    private ExchangeClient exchange;
    private ClientLoop.Factory clientLoops = ClientLoop.INLINE;
    private final Map<Integer, AtomicLong> clientReplies = new ConcurrentHashMap<>();
    private long generation;
    private Scheduler.Task pending;

    public Simulator(long seed) {
        this(seed, DEFAULT_START_REFERENCE_TICKS);
    }

    /** Runs on the given loop instead of a dedicated thread (e.g. the browser's event loop). */
    public Simulator(long seed, Scheduler scheduler) {
        this(seed, DEFAULT_START_REFERENCE_TICKS, scheduler);
    }

    public Simulator(long seed, long startReferenceTicks) {
        this(seed, startReferenceTicks, new ExecutorScheduler("simulator"));
    }

    /** Owns a fresh {@link DisruptorPipeline}, closed with this simulator. */
    public Simulator(long seed, long startReferenceTicks, Scheduler scheduler) {
        this(seed, startReferenceTicks, scheduler, new DisruptorPipeline(), true);
    }

    /**
     * Submits every command through {@code pipeline} (shared, not closed by this simulator). Attach other output
     * consumers before any command is published so they see the whole sequence.
     */
    public Simulator(long seed, long startReferenceTicks, Scheduler scheduler, Pipeline pipeline) {
        this(seed, startReferenceTicks, scheduler, pipeline, false);
    }

    private Simulator(long seed, long startReferenceTicks, Scheduler scheduler, Pipeline pipeline, boolean owns) {
        if (startReferenceTicks <= 0) {
            throw new IllegalArgumentException("startReferenceTicks must be positive");
        }
        this.seed = seed;
        this.startReferenceTicks = startReferenceTicks;
        this.random = new Random(seed);
        this.reference = new ReferencePrice(startReferenceTicks);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.gateway = new Gateway(pipeline);
        this.ownsPipeline = owns;
        pipeline.addConsumer(this::onResult);
        LOG.info("Simulator seed=" + seed);
    }

    public long seed() {
        return seed;
    }

    public long startingReferenceTicks() {
        return startReferenceTicks;
    }

    public boolean isRunning() {
        return running;
    }

    public double rate() {
        return rate;
    }

    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    /** Spawns the initial population if needed and starts the arrival loop. No-op if already running. */
    public void start() {
        run(() -> {
            if (running) {
                return;
            }
            populate();
            running = true;
            scheduleNext(++generation);
        });
    }

    /**
     * Connects simulated participants to the exchange: every participant spawned from now on registers through
     * {@code client} and trades only through it, on a loop from {@code loops}. Only while no participant exists.
     */
    public void connect(ExchangeClient client, ClientLoop.Factory loops) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(loops, "loops");
        run(() -> {
            if (!participants.isEmpty()) {
                throw new IllegalStateException("connect before participants exist");
            }
            exchange = client;
            clientLoops = loops;
        });
    }

    /** HTTP status &rarr; number of API replies simulated participants have received (0 = transport failure). */
    public Map<Integer, Long> clientReplies() {
        Map<Integer, Long> out = new java.util.TreeMap<>();
        clientReplies.forEach((status, n) -> out.put(status, n.get()));
        return out;
    }

    /**
     * Stops the arrival loop and lets every simulated participant leave (each cancels its own resting orders over
     * the API), then sweeps any non-user order still resting.
     */
    public void stop() {
        List<Participant> leaving = execute(() -> {
            running = false;
            generation++;
            if (pending != null) {
                pending.cancel();
                pending = null;
            }
            List<Participant> all = List.copyOf(participants);
            for (Participant participant : all) {
                exit(participant);
            }
            return all;
        });
        if (!scheduler.onLoop()) {
            for (Participant participant : leaving) {
                try {
                    if (!participant.loop().awaitClosed(EXIT_TIMEOUT_MILLIS)) {
                        LOG.warning(participant + " did not finish leaving in time");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        run(this::cancelAllNonUserOrders);
    }

    /** Sets the mean number of simulation steps (order arrivals) per second; takes effect immediately. */
    public void setRate(double ordersPerSecond) {
        if (!(ordersPerSecond > 0 && ordersPerSecond <= MAX_RATE)) {
            throw new IllegalArgumentException("rate must be in (0, " + MAX_RATE + "] orders/sec");
        }
        run(() -> {
            rate = ordersPerSecond;
            if (running) {
                if (pending != null) {
                    pending.cancel();
                }
                scheduleNext(++generation);
            }
        });
    }

    /** Stops the simulation and starts over with an empty engine and the starting reference price. */
    public void reset() {
        stop();
        run(() -> {
            long seq;
            while ((seq = pipeline.tryPublishReset()) == Pipeline.BUSY) {
                Thread.yield();
            }
            awaitApplied(seq);
            reference = new ReferencePrice(startReferenceTicks);
        });
    }

    @Override
    public void close() {
        if (!scheduler.isClosed()) {
            stop();
            scheduler.close();
            if (ownsPipeline) {
                pipeline.close();
            }
        }
    }

    // ---- command submission ---------------------------------------------------------------------------------------

    /**
     * Publishes {@code command} for {@code participantId} through the gateway and waits (on the simulator thread)
     * until its result has been applied here. Returns its events, or a single {@code BUSY} rejection.
     */
    public List<Event> submit(long participantId, Command command) {
        Objects.requireNonNull(command, "command");
        return execute(() -> publish(participantId, command));
    }

    /** The front door every producer uses; shared with the HTTP handlers. */
    public Gateway gateway() {
        return gateway;
    }

    public Pipeline pipeline() {
        return pipeline;
    }

    /** Simulator thread: applies results up to and including global sequence {@code seq}. */
    public void awaitApplied(long seq) {
        assertOnExecutor();
        while (applied < seq) {
            Result result = inbox.take(RESULT_TIMEOUT_MILLIS);
            if (result == null) {
                throw new IllegalStateException("no result for seq " + (applied + 1) + " within "
                        + RESULT_TIMEOUT_MILLIS + " ms");
            }
            apply(result);
        }
    }

    /** Simulator thread: applies every result for commands published so far (by any producer). */
    public void catchUp() {
        awaitApplied(pipeline.lastSequence());
    }

    /** Last global sequence applied to the replica, or -1. Simulator thread only. */
    public long appliedSeq() {
        assertOnExecutor();
        return applied;
    }

    /** Runs {@code task} on the simulator thread (inline if already on it) and waits for its result. */
    public <T> T execute(Supplier<T> task) {
        return scheduler.execute(task);
    }

    /** The loop this simulator runs on; tasks scheduled here are serialized with simulation steps. */
    public Scheduler scheduler() {
        return scheduler;
    }

    public void run(Runnable task) {
        execute(() -> {
            task.run();
            return null;
        });
    }

    /** The read-model replica of the engine. Only use it on the simulator thread, i.e. inside {@link #execute} or a {@link Listener}. */
    public MatchingEngine engine() {
        assertOnExecutor();
        return engine;
    }

    /** Owner of an order seen by this simulator, or {@link Order#NO_PARTICIPANT}. Simulator thread only. */
    public long participantOf(long orderId) {
        assertOnExecutor();
        OrderOwner owner = owners.get(orderId);
        return owner == null ? Order.NO_PARTICIPANT : owner.participantId();
    }

    /** Side of an order seen by this simulator, or null if unknown. Simulator thread only. */
    public Side sideOf(long orderId) {
        assertOnExecutor();
        OrderOwner owner = owners.get(orderId);
        return owner == null ? null : owner.side();
    }

    /** Display label: {@code "YOU"}, a persona label such as {@code "MM-1001"}, or {@code "P<id>"}. Thread-safe. */
    public String label(long participantId) {
        if (participantId == USER_PARTICIPANT_ID) {
            return USER_LABEL;
        }
        if (participantId == Order.NO_PARTICIPANT) {
            return "anon";
        }
        String label = labels.get(participantId);
        return label != null ? label : "P" + participantId;
    }

    public List<ParticipantInfo> participants() {
        return execute(() -> participants.stream()
                .map(p -> new ParticipantInfo(p.id(), p.label(), p.persona().kind()))
                .toList());
    }

    /** Simulator thread: submit through the gateway (never straight to an engine) and wait for the result. */
    private List<Event> publish(long participantId, Command command) {
        assertOnExecutor();
        if (command instanceof Command.Place place && place.order().participantId() != participantId) {
            throw new IllegalArgumentException("order participantId " + place.order().participantId()
                    + " does not match submitter " + participantId);
        }
        Gateway.Submission submission = gateway.submit(participantId, command, false);
        if (submission.busy() != null) {
            return List.of(submission.busy());
        }
        if (submission.rejection() != null) {
            throw new IllegalStateException("pre-trade check rejected: " + submission.rejection().message());
        }
        awaitApplied(submission.seq());
        return lastApplied.events();
    }

    /** Output-ring consumer thread: hand the result to the simulator thread. */
    private void onResult(ResultEvent result, boolean endOfBatch) {
        inbox.add(result.toResult());
        if (drainScheduled.compareAndSet(false, true) && !scheduler.isClosed()) {
            scheduler.schedule(this::drain, 1);
        }
    }

    private void drain() {
        drainScheduled.set(false);
        Result result;
        while ((result = inbox.poll()) != null) {
            apply(result);
        }
    }

    /** Simulator thread: applies one result to the replica and notifies listeners, in global sequence order. */
    private void apply(Result result) {
        if (result.seq() <= applied) {
            return;
        }
        if (result.seq() != applied + 1) {
            throw new IllegalStateException("result " + result.seq() + " out of order after " + applied);
        }
        applied = result.seq();
        lastApplied = result;
        if (result.reset()) {
            engine = new MatchingEngine();
            owners.clear();
            return;
        }
        Command command = result.command();
        engine.process(command);
        long participantId = result.participantId();
        List<Event> events = result.events();
        for (Event event : events) {
            if (event instanceof Event.OrderPlaced placed && command instanceof Command.Place place) {
                owners.put(placed.orderId(), new OrderOwner(participantId, place.order().side()));
            } else if (event instanceof Event.TradeExecuted executed) {
                creditFill(executed.trade().makerOrderId(), executed.trade().qty());
                creditFill(executed.trade().takerOrderId(), executed.trade().qty());
            }
        }
        for (Listener listener : listeners) {
            listener.onCommand(participantId, command, events);
        }
    }

    private void creditFill(long orderId, long qty) {
        OrderOwner owner = owners.get(orderId);
        if (owner == null) {
            return;
        }
        Participant participant = find(owner.participantId());
        if (participant != null) {
            participant.loop().post(() -> participant.persona().onFill(owner.side(), qty));
        }
    }

    // ---- simulation loop ------------------------------------------------------------------------------------------

    private void scheduleNext(long gen) {
        double delay = nextDelay();
        pending = scheduler.schedule(() -> {
            if (!running || gen != generation) {
                return;
            }
            try {
                step(delay);
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "simulation step failed", e);
            }
            scheduleNext(gen);
        }, Math.max(1, (long) (delay * 1e9)));
    }

    /** Exponential inter-arrival delay in seconds with mean {@code 1 / rate}. */
    double nextDelay() {
        return -Math.log(1 - random.nextDouble()) / rate;
    }

    /** One arrival: advance time and the reference price, apply churn, then let one participant act. */
    void step(double dt) {
        assertOnExecutor();
        reference.advance(dt, random);
        churn(dt);
        Participant actor = pickActor();
        if (actor != null) {
            act(actor);
        }
    }

    /** Runs {@code n} steps synchronously (for tests and deterministic replays). */
    void runSteps(int n) {
        run(() -> {
            for (int i = 0; i < n; i++) {
                step(nextDelay());
            }
        });
    }

    void populate() {
        assertOnExecutor();
        if (!participants.isEmpty()) {
            return;
        }
        for (int i = 0; i < INITIAL_PARTICIPANTS; i++) {
            Persona.Kind kind = i < 2 ? Persona.Kind.MARKET_MAKER : drawKind();
            addParticipant(kind, random.nextDouble(), Double.NEGATIVE_INFINITY);
        }
    }

    private void churn(double dt) {
        for (Participant participant : List.copyOf(participants)) {
            if (participants.size() <= MIN_PARTICIPANTS) {
                break;
            }
            if (random.nextDouble() < probability(exitHazard(participant), dt)) {
                exit(participant);
            }
        }
        while (participants.size() < MIN_PARTICIPANTS) {
            spawn();
        }
        if (participants.size() < MAX_PARTICIPANTS) {
            double hazard = participants.size() < TARGET_PARTICIPANTS ? REFILL_ENTRY_PER_SEC : ENTRY_PER_SEC;
            if (random.nextDouble() < probability(hazard, dt)) {
                spawn();
            }
        }
    }

    /** Exit rate per simulated second: low at baseline, spiking after a shock, higher for cautious participants. */
    double exitHazard(Participant participant) {
        double sensitivity = sensitivity(participant.persona().riskTolerance());
        return BASE_EXIT_PER_SEC * sensitivity * (1 + SHOCK_EXIT_MULTIPLIER * reference.shockIntensity() * sensitivity);
    }

    /** Maps risk tolerance in [0, 1] to a caution multiplier in [0.5, 1.5]. */
    static double sensitivity(double riskTolerance) {
        return 1.5 - riskTolerance;
    }

    static double probability(double hazardPerSec, double dt) {
        return 1 - Math.exp(-hazardPerSec * dt);
    }

    private Participant pickActor() {
        double total = 0;
        double[] weights = new double[participants.size()];
        for (int i = 0; i < weights.length; i++) {
            Participant p = participants.get(i);
            double ramp = Math.min(1, 0.2 + 0.8 * (reference.time() - p.spawnTime()) / ENTRY_RAMP_SEC);
            weights[i] = p.persona().activityWeight() * ramp;
            total += weights[i];
        }
        if (total <= 0) {
            return null;
        }
        double x = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            x -= weights[i];
            if (x < 0) {
                return participants.get(i);
            }
        }
        return participants.get(weights.length - 1);
    }

    private Persona.Kind drawKind() {
        double total = 0;
        for (Persona.Kind kind : Persona.Kind.values()) {
            total += kind.spawnWeight();
        }
        double x = random.nextDouble() * total;
        for (Persona.Kind kind : Persona.Kind.values()) {
            x -= kind.spawnWeight();
            if (x < 0) {
                return kind;
            }
        }
        return Persona.Kind.MARKET_MAKER;
    }

    private Participant spawn() {
        boolean haveMaker = participants.stream().anyMatch(p -> p.persona().kind() == Persona.Kind.MARKET_MAKER);
        Persona.Kind kind = haveMaker ? drawKind() : Persona.Kind.MARKET_MAKER;
        return addParticipant(kind, random.nextDouble(), reference.time());
    }

    Participant addParticipant(Persona.Kind kind, double riskTolerance, double spawnTime) {
        assertOnExecutor();
        if (exchange == null) {
            throw new IllegalStateException("no exchange connected; call connect() first");
        }
        ExchangeClient client = exchange;
        Random own = new Random(random.nextLong());
        ClientLoop loop = clientLoops.create(kind.labelPrefix());
        ExchangeClient.Credentials credentials = loop.call(() -> client.register(kind.labelPrefix()));
        if (credentials.participantId() == USER_PARTICIPANT_ID) {
            throw new IllegalStateException("exchange handed a simulated participant the user's id");
        }
        labels.put(credentials.participantId(), credentials.label());
        Participant participant = new Participant(credentials.participantId(), credentials.label(),
                credentials.apiKey(), Persona.create(kind, riskTolerance), spawnTime, own, loop);
        participants.add(participant);
        return participant;
    }

    /** Removes the participant; on its way out its client cancels its own resting orders over the API. */
    void exit(Participant participant) {
        assertOnExecutor();
        participants.remove(participant);
        ExchangeClient client = exchange;
        participant.loop().close(() -> {
            List<Long> ids = execute(() -> ownOrders(participant.id()).stream().map(Order::id).toList());
            for (long id : ids) {
                call(() -> client.cancel(participant.apiKey(), id));
            }
        });
    }

    /** Deals the participant a turn: a market snapshot taken here, acted on by its client loop. */
    void act(Participant participant) {
        assertOnExecutor();
        drain();
        Turn turn = new Turn(participant, exchange);
        participant.loop().tryDispatch(() -> {
            try {
                participant.persona().act(turn);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, participant + " turn failed", e);
            }
        });
    }

    private void call(Supplier<ExchangeClient.Reply> request) {
        int status;
        try {
            status = request.get().status();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "API call failed", e);
            status = 0;
        }
        clientReplies.computeIfAbsent(status, k -> new AtomicLong()).incrementAndGet();
    }

    private void cancelAllNonUserOrders() {
        for (Side side : Side.values()) {
            for (Order order : List.copyOf(engine.book().orders(side))) {
                if (order.participantId() != USER_PARTICIPANT_ID) {
                    publish(order.participantId(), new Command.Cancel(order.id()));
                }
            }
        }
    }

    /** The participant's resting orders in the replica, in priority order per side. Simulator thread only. */
    public List<Order> ownOrders(long participantId) {
        assertOnExecutor();
        List<Order> own = new ArrayList<>();
        for (Side side : Side.values()) {
            for (Order order : engine.book().orders(side)) {
                if (order.participantId() == participantId) {
                    own.add(order);
                }
            }
        }
        return own;
    }

    private Participant find(long participantId) {
        for (Participant participant : participants) {
            if (participant.id() == participantId) {
                return participant;
            }
        }
        return null;
    }

    List<Participant> activeParticipants() {
        assertOnExecutor();
        return List.copyOf(participants);
    }

    ReferencePrice reference() {
        assertOnExecutor();
        return reference;
    }

    private void assertOnExecutor() {
        if (!scheduler.onLoop()) {
            throw new IllegalStateException("must be called on the simulator thread (use execute())");
        }
    }

    /**
     * One turn of a simulated client: a snapshot of the market (taken on the simulator thread) plus actions that
     * become authenticated API calls. Safe to use on the client's own thread.
     */
    private final class Turn implements Persona.Context {
        private final Participant self;
        private final ExchangeClient client;
        private final long referenceTicks;
        private final double drift;
        private final double shockIntensity;
        private final List<OrderBook.Level> bids;
        private final List<OrderBook.Level> asks;
        private final List<Order> own;

        Turn(Participant self, ExchangeClient client) {
            this.self = self;
            this.client = client;
            this.referenceTicks = reference.ticks();
            this.drift = reference.drift();
            this.shockIntensity = reference.shockIntensity();
            this.bids = engine.book().depth(Side.BUY, SNAPSHOT_LEVELS);
            this.asks = engine.book().depth(Side.SELL, SNAPSHOT_LEVELS);
            List<Order> copies = new ArrayList<>();
            for (Order o : Simulator.this.ownOrders(self.id())) {
                copies.add(Order.limit(o.id(), o.participantId(), o.side(), o.price(), o.qtyRemaining(),
                        o.timeInForce()));
            }
            this.own = List.copyOf(copies);
        }

        @Override
        public Random random() {
            return self.random();
        }

        @Override
        public long referencePrice() {
            return referenceTicks;
        }

        @Override
        public double drift() {
            return drift;
        }

        @Override
        public double shockIntensity() {
            return shockIntensity;
        }

        @Override
        public OptionalLong bestPrice(Side side) {
            List<OrderBook.Level> levels = side == Side.BUY ? bids : asks;
            return levels.isEmpty() ? OptionalLong.empty() : OptionalLong.of(levels.get(0).price());
        }

        @Override
        public List<OrderBook.Level> depth(Side side, int levels) {
            List<OrderBook.Level> all = side == Side.BUY ? bids : asks;
            return all.subList(0, Math.min(Math.max(0, levels), all.size()));
        }

        @Override
        public List<Order> ownOrders() {
            return own;
        }

        @Override
        public void placeLimit(Side side, long price, long qty, TimeInForce timeInForce) {
            call(() -> client.place(self.apiKey(), side, OrderType.LIMIT, Math.max(1, price), Math.max(1, qty),
                    timeInForce));
        }

        @Override
        public void placeMarket(Side side, long qty) {
            call(() -> client.place(self.apiKey(), side, OrderType.MARKET, 0, Math.max(1, qty), TimeInForce.IOC));
        }

        @Override
        public void cancel(long orderId) {
            requireOwn(orderId);
            call(() -> client.cancel(self.apiKey(), orderId));
        }

        @Override
        public void amend(long orderId, Long newPrice, Long newQty) {
            requireOwn(orderId);
            Long price = newPrice == null ? null : Math.max(1, newPrice);
            Long qty = newQty == null ? null : Math.max(1, newQty);
            call(() -> client.amend(self.apiKey(), orderId, price, qty));
        }

        @Override
        public void shock(int direction) {
            run(() -> reference.jump(direction, random));
        }

        private void requireOwn(long orderId) {
            if (own.stream().noneMatch(o -> o.id() == orderId)) {
                throw new IllegalStateException(self + " tried to touch order " + orderId + " it does not own");
            }
        }
    }
}

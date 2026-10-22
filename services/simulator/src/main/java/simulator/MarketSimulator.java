package simulator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import java.util.logging.Logger;

/**
 * The external market simulator: the exchange's only source of liquidity and price discovery. It runs a population
 * of persona clients per symbol around a hidden per-symbol {@link ReferencePrice}; each client signs up for its own
 * real account and trades over the public API exactly like an outside participant ({@link ExchangeClient}), seeing
 * the market only through public market data ({@link MarketView}).
 *
 * <p>Clock and reproducibility: a seeded {@link SimClock} draws the Poisson arrivals and which symbol each goes to;
 * per-symbol seeded streams drive the reference price, churn (entries, exits) and the choice of actor. None of these
 * read exchange replies or market data, so the arrival process ({@link Arrival}s) is reproducible from the seed. What
 * personas then do with their turn depends on the book they see, and the engine's global {@code seq} depends on how
 * commands from many clients interleave over the network, so the exchange-side command stream is NOT reproducible
 * run to run. That is expected for a networked system.
 *
 * <p>Threading: all simulation state lives on one loop thread; each participant's API calls run on its own
 * {@link ClientLoop}.
 */
public final class MarketSimulator implements AutoCloseable {

    public static final double DEFAULT_RATE = 20;
    public static final double MAX_RATE = 400;
    public static final int DEFAULT_PARTICIPANTS = 4;
    public static final int MAX_PARTICIPANTS = 50;

    static final double BASE_EXIT_PER_SEC = 0.004;
    static final double SHOCK_EXIT_MULTIPLIER = 40;
    static final double ENTRY_PER_SEC = 0.04;
    static final double REFILL_ENTRY_PER_SEC = 0.3;
    static final double ENTRY_RAMP_SEC = 8;
    static final int SNAPSHOT_LEVELS = 10;
    private static final long EXIT_TIMEOUT_MILLIS = 10_000;
    private static final Logger LOG = Logger.getLogger(MarketSimulator.class.getName());

    /** A symbol to simulate and the reference price it starts from, in ticks. */
    public record SymbolSpec(String symbol, long startTicks) {
        public SymbolSpec {
            Objects.requireNonNull(symbol, "symbol");
            if (startTicks <= 0) {
                throw new IllegalArgumentException("startTicks must be positive");
            }
        }
    }

    /** Public view of a participant; omits hidden traits such as risk tolerance. */
    public record ParticipantInfo(String label, String symbol, Persona.Kind persona, long participantId,
            int restingOrders) {
    }

    public record SymbolStatus(String symbol, long referenceTicks, double shockIntensity,
            List<ParticipantInfo> participants) {
    }

    public record Status(boolean running, long seed, double rate, int targetParticipants, double simTime,
            long arrivals, List<SymbolStatus> symbols, Map<Integer, Long> replies, long accountsCreated) {
    }

    @FunctionalInterface
    public interface ArrivalListener {
        void onArrival(Arrival arrival);
    }

    private final ExchangeClient exchange;
    private final MarketView market;
    private final ClientLoop.Factory loops;
    private final boolean paced;
    private final ScheduledExecutorService loop;
    private volatile Thread loopThread;
    private final List<ArrivalListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Long, SimParticipant> orderOwners = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicLong> replies = new ConcurrentHashMap<>();
    private final List<ExchangeClient.Credentials> accounts = new CopyOnWriteArrayList<>();

    // Loop-confined.
    private final Map<String, SymbolMarket> markets = new LinkedHashMap<>();
    private long seed;
    private SimClock clock;
    private volatile boolean running;
    private volatile double rate = DEFAULT_RATE;
    private volatile int targetParticipants = DEFAULT_PARTICIPANTS;
    private long generation;
    private ScheduledFuture<?> pending;

    /**
     * @param paced release arrivals in wall-clock time (a running service); false for tests that drive
     *              {@link #runSteps} directly
     */
    public MarketSimulator(long seed, List<SymbolSpec> symbols, ExchangeClient exchange, MarketView market,
            ClientLoop.Factory loops, boolean paced) {
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("at least one symbol");
        }
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.market = Objects.requireNonNull(market, "market");
        this.loops = Objects.requireNonNull(loops, "loops");
        this.paced = paced;
        this.seed = seed;
        this.clock = new SimClock(seed);
        for (SymbolSpec spec : symbols) {
            markets.put(spec.symbol(), new SymbolMarket(spec.symbol(), spec.startTicks(), seed));
        }
        loop = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sim-clock");
            t.setDaemon(true);
            loopThread = t;
            return t;
        });
        market.addTradeListener(this::onTrade);
        LOG.info("Simulator seed=" + seed + " symbols=" + markets.keySet());
    }

    public void addArrivalListener(ArrivalListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    // ---- control --------------------------------------------------------------------------------------------------

    /** Spawns the initial population of every symbol and starts releasing arrivals. No-op if already running. */
    public void start() {
        run(() -> {
            if (running) {
                return;
            }
            populate();
            running = true;
            if (paced) {
                scheduleNext(++generation);
            }
        });
    }

    /** Stops the arrivals; every participant leaves, cancelling its own resting orders over the API. */
    public void stop() {
        List<SimParticipant> leaving = execute(() -> {
            running = false;
            generation++;
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
            List<SimParticipant> all = new ArrayList<>();
            for (SymbolMarket m : markets.values()) {
                for (SimParticipant p : List.copyOf(m.participants)) {
                    exit(m, p);
                    all.add(p);
                }
            }
            return all;
        });
        for (SimParticipant p : leaving) {
            try {
                if (!p.loop().awaitClosed(EXIT_TIMEOUT_MILLIS)) {
                    LOG.warning(p + " did not finish leaving in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Stops, then starts over from {@code newSeed}: fresh clock, reference prices and populations. */
    public void reset(long newSeed) {
        stop();
        run(() -> {
            seed = newSeed;
            clock = new SimClock(newSeed);
            for (SymbolMarket m : markets.values()) {
                m.reset(newSeed);
            }
            LOG.info("Simulator reset seed=" + newSeed);
        });
    }

    /** Mean arrivals (participant turns) per second across all symbols; takes effect immediately. */
    public void setRate(double ordersPerSecond) {
        if (!(ordersPerSecond > 0 && ordersPerSecond <= MAX_RATE)) {
            throw new IllegalArgumentException("rate must be in (0, " + MAX_RATE + "] arrivals/sec");
        }
        run(() -> {
            rate = ordersPerSecond;
            if (running && paced) {
                if (pending != null) {
                    pending.cancel(false);
                }
                scheduleNext(++generation);
            }
        });
    }

    /** Target population per symbol; churn drifts each population toward it. */
    public void setTargetParticipants(int n) {
        if (n < 1 || n > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("participants must be in [1, " + MAX_PARTICIPANTS + "]");
        }
        run(() -> targetParticipants = n);
    }

    public boolean isRunning() {
        return running;
    }

    public double rate() {
        return rate;
    }

    public int targetParticipants() {
        return targetParticipants;
    }

    public Status status() {
        return execute(() -> {
            List<SymbolStatus> symbols = new ArrayList<>();
            for (SymbolMarket m : markets.values()) {
                List<ParticipantInfo> ps = new ArrayList<>();
                for (SimParticipant p : m.participants) {
                    ExchangeClient.Credentials c = p.credentials();
                    ps.add(new ParticipantInfo(p.label(), m.symbol, p.persona().kind(),
                            c == null ? -1 : c.participantId(), p.orders().snapshot().size()));
                }
                symbols.add(new SymbolStatus(m.symbol, m.reference.ticks(), m.reference.shockIntensity(), ps));
            }
            return new Status(running, seed, rate, targetParticipants, clock.time(), clock.arrivals(), symbols,
                    replyCounts(), accounts.size());
        });
    }

    /** HTTP status &rarr; number of API replies participants received (0 = transport failure). */
    public Map<Integer, Long> replyCounts() {
        Map<Integer, Long> out = new TreeMap<>();
        replies.forEach((status, n) -> out.put(status, n.get()));
        return out;
    }

    /** Every account this simulator has signed up, in order. */
    public List<ExchangeClient.Credentials> accounts() {
        return List.copyOf(accounts);
    }

    @Override
    public void close() {
        if (!loop.isShutdown()) {
            stop();
            loop.shutdownNow();
        }
    }

    // ---- arrival process ------------------------------------------------------------------------------------------

    private void scheduleNext(long gen) {
        double dt = clock.nextDelay(rate);
        pending = loop.schedule(() -> {
            if (!running || gen != generation) {
                return;
            }
            try {
                step(dt);
            } catch (RuntimeException e) {
                LOG.log(java.util.logging.Level.SEVERE, "simulation step failed", e);
            }
            scheduleNext(gen);
        }, Math.max(1, (long) (dt * 1e9)), TimeUnit.NANOSECONDS);
    }

    /** Runs {@code n} arrivals back to back on the loop, without wall-clock pacing (tests, replays). */
    public void runSteps(int n) {
        run(() -> {
            if (!running) {
                throw new IllegalStateException("start() first");
            }
            for (int i = 0; i < n; i++) {
                step(clock.nextDelay(rate));
            }
        });
    }

    /** One arrival: advance every symbol's time and reference, apply churn, then deal one participant a turn. */
    private void step(double dt) {
        clock.advance(dt);
        for (SymbolMarket m : markets.values()) {
            m.reference.advance(dt, m.random);
            churn(m, dt);
        }
        List<SymbolMarket> all = List.copyOf(markets.values());
        SymbolMarket m = all.get(clock.pick(all.size()));
        SimParticipant actor = pickActor(m);
        if (actor == null) {
            return;
        }
        int shock = 0;
        if (actor.persona().kind() == Persona.Kind.WHALE) {
            shock = m.random.nextBoolean() ? 1 : -1;
            m.reference.jump(shock, m.random);
        }
        emit(m, Arrival.Kind.TURN, actor, shock);
        act(m, actor, shock);
    }

    private void populate() {
        for (SymbolMarket m : markets.values()) {
            if (!m.participants.isEmpty()) {
                continue;
            }
            int initial = targetParticipants + 1;
            for (int i = 0; i < initial; i++) {
                Persona.Kind kind = i < Math.min(2, initial) ? Persona.Kind.MARKET_MAKER : drawKind(m.random);
                addParticipant(m, kind, m.random.nextDouble(), Double.NEGATIVE_INFINITY);
            }
        }
    }

    int minParticipants() {
        return Math.max(2, (targetParticipants + 1) / 2);
    }

    int maxParticipants() {
        return Math.max(minParticipants(), 2 * targetParticipants);
    }

    private void churn(SymbolMarket m, double dt) {
        for (SimParticipant p : List.copyOf(m.participants)) {
            if (m.participants.size() <= minParticipants()) {
                break;
            }
            if (m.random.nextDouble() < probability(exitHazard(m, p), dt)) {
                exit(m, p);
            }
        }
        while (m.participants.size() < minParticipants()) {
            spawn(m);
        }
        if (m.participants.size() < maxParticipants()) {
            double hazard = m.participants.size() < targetParticipants ? REFILL_ENTRY_PER_SEC : ENTRY_PER_SEC;
            if (m.random.nextDouble() < probability(hazard, dt)) {
                spawn(m);
            }
        }
    }

    /** Exit rate per simulated second: low at baseline, spiking after a shock, higher for cautious participants. */
    static double exitHazard(SymbolMarket m, SimParticipant p) {
        double sensitivity = sensitivity(p.persona().riskTolerance());
        return BASE_EXIT_PER_SEC * sensitivity
                * (1 + SHOCK_EXIT_MULTIPLIER * m.reference.shockIntensity() * sensitivity);
    }

    /** Maps risk tolerance in [0, 1] to a caution multiplier in [0.5, 1.5]. */
    static double sensitivity(double riskTolerance) {
        return 1.5 - riskTolerance;
    }

    static double probability(double hazardPerSec, double dt) {
        return 1 - Math.exp(-hazardPerSec * dt);
    }

    private SimParticipant pickActor(SymbolMarket m) {
        double total = 0;
        double[] weights = new double[m.participants.size()];
        for (int i = 0; i < weights.length; i++) {
            SimParticipant p = m.participants.get(i);
            double ramp = Math.min(1, 0.2 + 0.8 * (m.reference.time() - p.spawnTime()) / ENTRY_RAMP_SEC);
            weights[i] = p.persona().activityWeight() * ramp;
            total += weights[i];
        }
        if (total <= 0) {
            return null;
        }
        double x = m.random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            x -= weights[i];
            if (x < 0) {
                return m.participants.get(i);
            }
        }
        return m.participants.get(weights.length - 1);
    }

    private static Persona.Kind drawKind(Random random) {
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

    private void spawn(SymbolMarket m) {
        Persona.Kind kind = m.hasMaker() ? drawKind(m.random) : Persona.Kind.MARKET_MAKER;
        addParticipant(m, kind, m.random.nextDouble(), m.reference.time());
    }

    /** Adds a participant; its client signs up for its own account first thing on its loop. */
    private SimParticipant addParticipant(SymbolMarket m, Persona.Kind kind, double riskTolerance,
            double spawnTime) {
        String label = kind.labelPrefix() + "_" + m.symbol + "_" + (++m.nextSlot);
        SimParticipant p = new SimParticipant(label, m.symbol, Persona.create(kind, riskTolerance), spawnTime,
                new Random(m.random.nextLong()), loops.create(label));
        m.participants.add(p);
        emit(m, Arrival.Kind.ENTER, p, 0);
        p.loop().post(() -> {
            try {
                ExchangeClient.Credentials c = exchange.register(label);
                accounts.add(c);
                p.setCredentials(c);
            } catch (RuntimeException e) {
                LOG.log(java.util.logging.Level.WARNING, label + " could not sign up", e);
                count(0);
            }
        });
        return p;
    }

    /** Removes the participant; on its way out its client cancels its own resting orders over the API. */
    private void exit(SymbolMarket m, SimParticipant p) {
        m.participants.remove(p);
        emit(m, Arrival.Kind.EXIT, p, 0);
        p.loop().close(() -> {
            ExchangeClient.Credentials c = p.credentials();
            if (c == null) {
                return;
            }
            for (OwnOrder o : p.orders().snapshot()) {
                ExchangeClient.Reply r = call(() -> exchange.cancel(c.apiKey(), p.symbol(), o.id()));
                p.orders().removed(o.id());
                orderOwners.remove(o.id());
                if (r != null && !r.ok()) {
                    LOG.fine(p + " exit cancel of " + o.id() + " -> " + r.status());
                }
            }
        });
    }

    private void act(SymbolMarket m, SimParticipant p, int shock) {
        Turn turn = new Turn(m, p, shock);
        p.loop().tryDispatch(() -> {
            if (p.credentials() == null) {
                return;
            }
            try {
                p.persona().act(turn);
            } catch (RuntimeException e) {
                LOG.log(java.util.logging.Level.WARNING, p + " turn failed", e);
            }
        });
    }

    private void emit(SymbolMarket m, Arrival.Kind kind, SimParticipant p, int shock) {
        if (listeners.isEmpty()) {
            return;
        }
        Arrival a = new Arrival(clock.arrivals(), clock.time(), m.symbol, kind, p.label(), p.persona().kind(),
                m.reference.ticks(), shock);
        for (ArrivalListener l : listeners) {
            l.onArrival(a);
        }
    }

    /** Feed thread: a maker-side print on one of our resting orders is a fill for its owner. */
    private void onTrade(MarketView.Trade trade) {
        SimParticipant owner = orderOwners.get(trade.makerOrderId());
        if (owner == null) {
            return;
        }
        Side side = owner.orders().filled(trade.makerOrderId(), trade.qty());
        if (!owner.orders().owns(trade.makerOrderId())) {
            orderOwners.remove(trade.makerOrderId());
        }
        if (side != null) {
            long qty = trade.qty();
            owner.loop().post(() -> owner.persona().onFill(side, qty));
        }
    }

    private ExchangeClient.Reply call(Supplier<ExchangeClient.Reply> request) {
        ExchangeClient.Reply reply;
        try {
            reply = request.get();
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.FINE, "API call failed", e);
            count(0);
            return null;
        }
        count(reply.status());
        return reply;
    }

    private void count(int status) {
        replies.computeIfAbsent(status, k -> new AtomicLong()).incrementAndGet();
    }

    // ---- loop helpers -----------------------------------------------------------------------------------------

    <T> T execute(Supplier<T> task) {
        if (Thread.currentThread() == loopThread) {
            return task.get();
        }
        Future<T> future = loop.submit(task::get);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    void run(Runnable task) {
        execute(() -> {
            task.run();
            return null;
        });
    }

    /**
     * One turn: a market-data snapshot taken on the simulator loop, plus actions that become authenticated API
     * calls on the participant's own client loop.
     */
    private final class Turn implements Persona.Context {
        private final SimParticipant self;
        private final String symbol;
        private final long referenceTicks;
        private final double drift;
        private final double shockIntensity;
        private final int shockDirection;
        private final List<Level> bids;
        private final List<Level> asks;
        private final List<OwnOrder> own;

        Turn(SymbolMarket m, SimParticipant self, int shockDirection) {
            this.self = self;
            this.symbol = m.symbol;
            this.referenceTicks = m.reference.ticks();
            this.drift = m.reference.drift();
            this.shockIntensity = m.reference.shockIntensity();
            this.shockDirection = shockDirection;
            this.bids = List.copyOf(market.depth(symbol, Side.BUY, SNAPSHOT_LEVELS));
            this.asks = List.copyOf(market.depth(symbol, Side.SELL, SNAPSHOT_LEVELS));
            this.own = self.orders().snapshot();
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
        public int shockDirection() {
            return shockDirection;
        }

        @Override
        public java.util.OptionalLong bestPrice(Side side) {
            List<Level> levels = side == Side.BUY ? bids : asks;
            return levels.isEmpty() ? java.util.OptionalLong.empty()
                    : java.util.OptionalLong.of(levels.get(0).price());
        }

        @Override
        public List<Level> depth(Side side, int levels) {
            List<Level> all = side == Side.BUY ? bids : asks;
            return all.subList(0, Math.min(Math.max(0, levels), all.size()));
        }

        @Override
        public List<OwnOrder> ownOrders() {
            return own;
        }

        @Override
        public void placeLimit(Side side, long price, long qty, TimeInForce timeInForce) {
            place(side, OrderType.LIMIT, Math.max(1, price), Math.max(1, qty), timeInForce);
        }

        @Override
        public void placeMarket(Side side, long qty) {
            place(side, OrderType.MARKET, 0, Math.max(1, qty), TimeInForce.IOC);
        }

        private void place(Side side, OrderType type, long price, long qty, TimeInForce tif) {
            String key = self.credentials().apiKey();
            ExchangeClient.Reply r = call(() -> exchange.place(key, symbol, side, type, price, qty, tif));
            if (r == null || !r.ok()) {
                return;
            }
            if (r.filledQty() > 0) {
                self.persona().onFill(side, r.filledQty());
            }
            if (r.restingQty() > 0 && r.orderId() >= 0) {
                orderOwners.put(r.orderId(), self);
                self.orders().placed(r.orderId(), side, price, r.restingQty());
            }
        }

        @Override
        public void cancel(long orderId) {
            requireOwn(orderId);
            ExchangeClient.Reply r = call(() -> exchange.cancel(self.credentials().apiKey(), symbol, orderId));
            // Gone either way: cancelled now, or already filled/cancelled (404/409).
            if (r != null) {
                self.orders().removed(orderId);
                orderOwners.remove(orderId);
            }
        }

        @Override
        public void amend(long orderId, Long newPrice, Long newQty) {
            requireOwn(orderId);
            Long price = newPrice == null ? null : Math.max(1, newPrice);
            Long qty = newQty == null ? null : Math.max(1, newQty);
            ExchangeClient.Reply r = call(() -> exchange.amend(self.credentials().apiKey(), symbol, orderId, price,
                    qty));
            if (r == null) {
                return;
            }
            if (r.ok()) {
                Side side = sideOf(orderId);
                if (r.filledQty() > 0 && side != null) {
                    self.persona().onFill(side, r.filledQty());
                }
                if (r.restingQty() <= 0 && r.filledQty() > 0) {
                    self.orders().removed(orderId);
                    orderOwners.remove(orderId);
                } else {
                    self.orders().amended(orderId, price, r.restingQty() > 0 ? Long.valueOf(r.restingQty()) : qty);
                }
            } else if (r.status() == 404 || r.status() == 409) {
                self.orders().removed(orderId);
                orderOwners.remove(orderId);
            }
        }

        private Side sideOf(long orderId) {
            for (OwnOrder o : own) {
                if (o.id() == orderId) {
                    return o.side();
                }
            }
            return null;
        }

        private void requireOwn(long orderId) {
            if (own.stream().noneMatch(o -> o.id() == orderId)) {
                throw new IllegalStateException(self + " tried to touch order " + orderId + " it does not own");
            }
        }
    }
}

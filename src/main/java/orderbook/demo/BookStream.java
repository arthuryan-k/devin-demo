package orderbook.demo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import orderbook.sim.Simulator;

/**
 * Pushes market state to WebSocket clients as sequenced, coalesced deltas.
 *
 * <p>Frames (server to client, JSON text):
 * <ul>
 *   <li>{@code {"type":"snapshot","seq":N,"reason":"connect|reset|resync","orders":[...],"trades":[...],"events":[...],
 *       "stats":{...},"account":{...},"simulation":{...}}}: full state; {@code orders} is every resting order.
 *   <li>{@code {"type":"delta","seq":N,"prevSeq":N-1,"orders":[upserts],"removed":[ids],"trades":[new, newest first],
 *       "events":[new feed entries],"stats":{...},"account":{...},"simulation":{...}}}: changes since frame N-1.
 * </ul>
 * Upserts carry an order's full current state, so applying one twice is harmless; trades carry a running number
 * {@code n} and events a feed {@code seq} so clients can drop duplicates. A client that sees a {@code prevSeq} other
 * than the last {@code seq} it applied must reconnect.
 *
 * <p>Changes are collected per command and flushed at most once per {@code flushMillis}, so frame rate per client is
 * bounded no matter how fast orders arrive, and nothing is sent while the market is idle. Reconnecting with
 * {@code /ws?since=N} replays the buffered deltas after N if they are still held, and sends a snapshot otherwise.
 *
 * <p>Threading: all state here is confined to the simulator thread; only the {@link Outbox} writes leave it.
 */
final class BookStream {

    static final int MAX_QUEUED_FRAMES = 64;
    static final int REPLAY_FRAMES = 256;
    static final int SNAPSHOT_EVENTS = 100;
    private static final System.Logger LOG = System.getLogger(BookStream.class.getName());

    /** JSON views of the server state. Simulator thread only. */
    interface Source {
        /** The resting order, or null if it is no longer in the book. */
        String order(long orderId);

        List<String> restingOrders();

        /** Recent trades, newest first, as a JSON array. */
        String trades();

        /** Feed entries with {@code seq > since}, as a JSON array. */
        String feedSince(long since);

        long feedSeq();

        String stats();

        String account();

        String simulation();
    }

    private record Frame(long seq, String json) {
    }

    private final Simulator simulator;
    private final Source source;
    private final long flushMillis;
    private ScheduledExecutorService flusher;

    // Confined to the simulator thread.
    private final Set<Long> dirtyOrders = new LinkedHashSet<>();
    private final List<String> newTrades = new ArrayList<>();
    private final Set<Outbox> clients = new LinkedHashSet<>();
    private final ArrayDeque<Frame> replay = new ArrayDeque<>();
    private long seq;
    private long replayFloor;
    private long feedSent;
    private String lastTail = "";

    BookStream(Simulator simulator, Source source, long flushMillis) {
        this.simulator = simulator;
        this.source = source;
        this.flushMillis = flushMillis;
    }

    void start() {
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "book-stream");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleWithFixedDelay(this::tick, flushMillis, flushMillis, TimeUnit.MILLISECONDS);
    }

    void close() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        try {
            simulator.run(() -> {
                for (Outbox client : List.copyOf(clients)) {
                    client.close();
                }
                clients.clear();
            });
        } catch (RejectedExecutionException e) {
            // simulator already shut down
        }
    }

    /** Registers a client and queues its first frames: missed deltas when resumable, a snapshot otherwise. */
    Outbox connect(Outbox.Transport transport, OptionalLong since) {
        Outbox out = new Outbox(transport, MAX_QUEUED_FRAMES);
        simulator.run(() -> {
            clients.add(out);
            if (since.isPresent() && since.getAsLong() >= replayFloor && since.getAsLong() <= seq) {
                for (Frame frame : replay) {
                    if (frame.seq() > since.getAsLong()) {
                        out.enqueue(frame.json(), this::resyncSnapshot);
                    }
                }
            } else {
                out.enqueue(snapshot("connect"), this::resyncSnapshot);
            }
        });
        return out;
    }

    void disconnect(Outbox out) {
        out.close();
        try {
            simulator.run(() -> clients.remove(out));
        } catch (RejectedExecutionException | IllegalStateException e) {
            // simulator shut down or interrupted during server stop; flush() also drops closed clients
        }
    }

    /** Simulator thread: the order's state changed. */
    void markOrder(long orderId) {
        dirtyOrders.add(orderId);
    }

    /** Simulator thread: a trade was appended to the tape. */
    void onTrade(String tradeJson) {
        newTrades.add(tradeJson);
    }

    /** Simulator thread: the engine and account were replaced; every client gets a fresh snapshot. */
    void onReset() {
        dirtyOrders.clear();
        newTrades.clear();
        feedSent = source.feedSeq();
        lastTail = tail();
        seq++;
        replay.clear();
        replayFloor = seq;
        String snapshot = snapshot("reset");
        for (Outbox client : clients) {
            client.enqueue(snapshot, this::resyncSnapshot);
        }
    }

    private void tick() {
        try {
            simulator.run(this::flush);
        } catch (RejectedExecutionException e) {
            // shutting down
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "book stream flush failed", e);
        }
    }

    private void flush() {
        clients.removeIf(Outbox::isClosed);
        String tail = tail();
        long feedSeq = source.feedSeq();
        if (dirtyOrders.isEmpty() && newTrades.isEmpty() && feedSeq == feedSent && tail.equals(lastTail)) {
            return;
        }
        List<String> upserts = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (long id : dirtyOrders) {
            String order = source.order(id);
            if (order == null) {
                removed.add(Long.toString(id));
            } else {
                upserts.add(order);
            }
        }
        List<String> trades = new ArrayList<>(newTrades);
        Collections.reverse(trades);
        String events = source.feedSince(feedSent);
        dirtyOrders.clear();
        newTrades.clear();
        feedSent = feedSeq;
        lastTail = tail;

        seq++;
        String frame = "{\"type\":\"delta\",\"seq\":" + seq + ",\"prevSeq\":" + (seq - 1)
                + ",\"orders\":" + Json.array(upserts) + ",\"removed\":" + Json.array(removed)
                + ",\"trades\":" + Json.array(trades) + ",\"events\":" + events + "," + tail + "}";
        replay.addLast(new Frame(seq, frame));
        while (replay.size() > REPLAY_FRAMES) {
            replayFloor = replay.removeFirst().seq();
        }
        for (Outbox client : clients) {
            client.enqueue(frame, this::resyncSnapshot);
        }
    }

    private String resyncSnapshot() {
        return snapshot("resync");
    }

    private String snapshot(String reason) {
        long feedSeq = source.feedSeq();
        return "{\"type\":\"snapshot\",\"seq\":" + seq + ",\"reason\":" + Json.str(reason)
                + ",\"orders\":" + Json.array(source.restingOrders()) + ",\"trades\":" + source.trades()
                + ",\"events\":" + source.feedSince(Math.max(0, feedSeq - SNAPSHOT_EVENTS)) + "," + tail() + "}";
    }

    private String tail() {
        return "\"stats\":" + source.stats() + ",\"account\":" + source.account() + ",\"simulation\":"
                + source.simulation();
    }
}

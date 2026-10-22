package orderbook.bus;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

import orderbook.Command;
import orderbook.CommandLog;
import orderbook.EngineState;
import orderbook.MatchingEngine;
import orderbook.Symbols;
import orderbook.journal.CommandCodec;
import orderbook.journal.Journal;
import orderbook.marketdata.MarketDataPublisher;
import orderbook.marketdata.Outbox;
import orderbook.pipeline.DisruptorPipeline;
import orderbook.pipeline.Pipeline;
import orderbook.pipeline.ResultEvent;

/**
 * One symbol's matching engine on the bus: {@code commands.<symbol>} → input ring → engine → output ring →
 * journal, then {@code events.<symbol>}; market data to {@code marketdata.<symbol>}.
 *
 * <p><b>Single writer.</b> One consumer thread per symbol is the only producer of the input ring, so ring order is
 * topic order and every command gets the next global {@code seq}. The topic has one partition, so that order is total.
 * A full ring is backpressure: the consumer waits for a slot rather than rejecting.
 *
 * <p><b>Journal first.</b> A command's events record is published only after the journal has written it, and the
 * events record carries the command's {@code commandOffset}. On restart the service reads the last events record
 * (seq P) and the journal (last seq J), rebuilds the engine as of {@code R = min(P, J)} from the journal, and
 * re-consumes commands after the one sequenced at R. Re-processing is deterministic, so commands up to J are checked
 * against the journal instead of re-journaled, and events up to P are not re-published: every seq reaches
 * {@code events.<symbol>} exactly once, in order. Market data is re-derived from the restored book and starts with a
 * snapshot.
 */
public final class SymbolBus implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SymbolBus.class.getName());

    /** Settings for one symbol. */
    public record Config(String symbol, String bootstrapServers, Journal journal, int snapshotEvery,
            int marketDataSnapshotEvery, int inputRingSize, int outputRingSize) {

        public Config {
            Symbols.requireValid(symbol);
            Objects.requireNonNull(bootstrapServers, "bootstrapServers");
            Objects.requireNonNull(journal, "journal");
        }

        public static Config defaults(String symbol, String bootstrapServers, Journal journal) {
            return new Config(symbol, bootstrapServers, journal, 10_000, 1000, DisruptorPipeline.DEFAULT_INPUT_SIZE,
                    DisruptorPipeline.DEFAULT_OUTPUT_SIZE);
        }
    }

    private record Pending(long seq, String correlationId, long offset) {
    }

    private final Config config;
    private final String symbol;
    private final Producer<String, String> producer;
    private final Consumer<Throwable> onFatal;
    private final Queue<Pending> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong journaled = new AtomicLong();
    private final AtomicLong ackedOffset = new AtomicLong(-1);
    private final AtomicLong ackedSeq = new AtomicLong(-1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final MarketDataPublisher marketData = new MarketDataPublisher();
    private volatile boolean running = true;

    private long restoredSeq;
    private long publishedThrough;
    private long journaledThrough;
    private Map<Long, String> journalTail;
    private long resumeOffset;
    private long nextOrderId;
    private LiveOrders liveOrders;
    private DisruptorPipeline pipeline;
    private KafkaConsumer<String, String> consumer;
    private Thread ingress;

    /** Recovers, then starts consuming. {@code onFatal} is told about unrecoverable errors (crash-only: restart). */
    public SymbolBus(Config config, Producer<String, String> producer, Consumer<Throwable> onFatal) {
        this.config = config;
        this.symbol = config.symbol();
        this.producer = Objects.requireNonNull(producer, "producer");
        this.onFatal = onFatal != null ? onFatal : t -> { };
        recover();
        start();
    }

    // ---- recovery ---------------------------------------------------------------------------------------------------

    private void recover() {
        Journal journal = config.journal();
        journaledThrough = journal.lastSeq();
        BusPosition.Last last = BusPosition.lastEvents(config.bootstrapServers(), Topics.events(symbol));
        publishedThrough = last == null ? -1 : last.seq();
        restoredSeq = Math.min(publishedThrough, journaledThrough);
        Journal.Recovery recovery = journal.load(restoredSeq);
        if (recovery.lastSeq() != restoredSeq) {
            throw new IllegalStateException(symbol + ": journal cannot be rebuilt at seq " + restoredSeq
                    + " (recovered through " + recovery.lastSeq() + ")");
        }
        MatchingEngine engine = recovery.toEngine(symbol, CommandLog.NONE);
        journalTail = new HashMap<>();
        for (CommandCodec.Sequenced e : journal.load().tail()) {
            if (e.seq() > restoredSeq) {
                journalTail.put(e.seq(), CommandCodec.encode(e.command()));
            }
        }
        if (restoredSeq < 0) {
            resumeOffset = -1;
        } else {
            long offsetOfRestored = BusPosition.commandOffsetAt(config.bootstrapServers(), Topics.events(symbol),
                    last, restoredSeq);
            resumeOffset = offsetOfRestored + 1;
        }
        nextOrderId = alignOrderId(engine.nextOrderId());
        EngineState state = engine.exportState();
        marketData.restore(state, restoredSeq);
        liveOrders = new LiveOrders(state);
        journaled.set(restoredSeq);
        ackedSeq.set(restoredSeq);
        pipeline = new DisruptorPipeline(engine, restoredSeq + 1, config.inputRingSize(), config.outputRingSize());
        pipeline.setSnapshotInterval(config.snapshotEvery());
        LOG.info(() -> symbol + ": journal through " + journaledThrough + ", bus through " + publishedThrough
                + ", resuming after seq " + restoredSeq + " at command offset " + resumeOffset);
    }

    // ---- wiring -----------------------------------------------------------------------------------------------------

    private void start() {
        pipeline.addConsumer(this::journal);
        pipeline.addConsumer(this::publishEvents);
        marketData.subscribe(new KafkaTransport(Topics.marketData(symbol)));
        long[] lastSnapshot = {restoredSeq};
        pipeline.addConsumer((result, endOfBatch) -> {
            marketData.onResult(result, endOfBatch);
            if (config.marketDataSnapshotEvery() > 0
                    && result.seq() - lastSnapshot[0] >= config.marketDataSnapshotEvery()) {
                lastSnapshot[0] = result.seq();
                send(Topics.marketData(symbol), marketData.snapshot(), null);
            }
        });
        consumer = new KafkaConsumer<>(consumerProps(config.bootstrapServers()));
        ingress = new Thread(this::consume, "bus-ingress-" + symbol);
        ingress.setDaemon(true);
        ingress.start();
    }

    static Properties consumerProps(String bootstrap) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "matching-engine");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return p;
    }

    /**
     * Engine-assigned order ids are striped by symbol ({@code id % ORDER_ID_STRIDE == listing index + 1}), so they are
     * unique exchange-wide while each symbol still derives its next id from its own recovered state alone.
     */
    public static final int ORDER_ID_STRIDE = 16;

    private long alignOrderId(long atLeast) {
        long residue = Symbols.ALL.indexOf(symbol) + 1;
        return atLeast + Math.floorMod(residue - atLeast, ORDER_ID_STRIDE);
    }

    // ---- ingress: commands.<symbol> → input ring ---------------------------------------------------------------------

    private void consume() {
        TopicPartition tp = new TopicPartition(Topics.commands(symbol), 0);
        long expected = restoredSeq + 1;
        long committed = -1;
        try {
            consumer.assign(List.of(tp));
            if (resumeOffset < 0) {
                consumer.seekToBeginning(List.of(tp));
            } else {
                consumer.seek(tp, resumeOffset);
            }
            while (running) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                    if (!running) {
                        break;
                    }
                    BusCommand command;
                    try {
                        command = BusCodec.decodeCommand(record.value() == null ? "" : record.value(), symbol);
                    } catch (BusCodec.InvalidCommandException e) {
                        deadLetter(record, e.getMessage());
                        continue;
                    }
                    if (command.needsOrderId()) {
                        command = command.withOrderId(nextOrderId);
                    }
                    if (command.command() instanceof Command.Place place) {
                        nextOrderId = Math.max(nextOrderId, alignOrderId(place.order().id() + 1));
                    }
                    pending.add(new Pending(expected, command.correlationId(), record.offset()));
                    long seq;
                    while ((seq = pipeline.tryPublish(command.participantId(), command.command(), false))
                            == Pipeline.BUSY) {
                        if (!running) {
                            return;
                        }
                        LockSupport.parkNanos(50_000);
                    }
                    if (seq != expected) {
                        throw new IllegalStateException(symbol + ": sequenced " + seq + ", expected " + expected);
                    }
                    expected++;
                }
                long acked = ackedOffset.get();
                if (acked > committed) {
                    committed = acked;
                    consumer.commitAsync(Map.of(tp, new OffsetAndMetadata(acked + 1, "seq=" + ackedSeq.get())), null);
                }
            }
        } catch (WakeupException e) {
            // closing
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            consumer.close(Duration.ofSeconds(2));
        }
    }

    private void deadLetter(ConsumerRecord<String, String> record, String reason) {
        LOG.warning(() -> symbol + ": dead-lettering command offset " + record.offset() + ": " + reason);
        ProducerRecord<String, String> dlq = new ProducerRecord<>(Topics.deadLetter(symbol), 0, record.key(),
                record.value());
        dlq.headers().add("error", reason.getBytes(StandardCharsets.UTF_8));
        dlq.headers().add("source.offset", Long.toString(record.offset()).getBytes(StandardCharsets.UTF_8));
        producer.send(dlq, (md, e) -> {
            if (e != null) {
                fail(e);
            }
        });
    }

    // ---- output ring consumers --------------------------------------------------------------------------------------

    private void journal(ResultEvent result, boolean endOfBatch) {
        long seq = result.seq();
        if (result.command() != null) {
            if (seq <= journaledThrough) {
                String expected = journalTail.remove(seq);
                String actual = CommandCodec.encode(result.command());
                if (!actual.equals(expected)) {
                    fail(new IllegalStateException(symbol + ": replay diverged from the journal at seq " + seq
                            + ": journal " + expected + ", bus " + actual));
                    return;
                }
            } else {
                config.journal().append(seq, result.command());
            }
        }
        if (result.snapshot() != null && seq > journaledThrough) {
            config.journal().writeSnapshot(seq, result.snapshot());
        }
        journaled.set(seq);
    }

    private void publishEvents(ResultEvent result, boolean endOfBatch) {
        if (result.command() == null) {
            return;
        }
        long seq = result.seq();
        liveOrders.place(result.command(), result.events());
        Pending p = pending.poll();
        if (p == null || p.seq() != seq) {
            fail(new IllegalStateException(symbol + ": no pending command for seq " + seq + " (" + p + ")"));
            return;
        }
        while (journaled.get() < seq) {
            if (!running) {
                return;
            }
            LockSupport.parkNanos(20_000);
        }
        if (seq <= publishedThrough) {
            liveOrders.apply(result.events());
            acked(p);
            return;
        }
        String value = BusCodec.encodeEvents(symbol, seq, result.participantId(), p.correlationId(), p.offset(),
                result.command(), result.events(), liveOrders, System.currentTimeMillis());
        liveOrders.apply(result.events());
        send(Topics.events(symbol), value, () -> acked(p));
    }

    private void acked(Pending p) {
        ackedSeq.set(p.seq());
        ackedOffset.set(p.offset());
    }

    private void send(String topic, String value, Runnable onAck) {
        producer.send(new ProducerRecord<>(topic, 0, symbol, value), (md, e) -> {
            if (e != null) {
                fail(e);
            } else if (onAck != null) {
                onAck.run();
            }
        });
    }

    /** Market data straight to the producer; it buffers and orders sends, so the outbox never backs up. */
    private final class KafkaTransport implements Outbox.Transport {
        private final String topic;

        KafkaTransport(String topic) {
            this.topic = topic;
        }

        @Override
        public void send(String text, Runnable onSuccess, Consumer<Throwable> onFailure) {
            if (!text.isEmpty()) {
                SymbolBus.this.send(topic, text, null);
            }
            onSuccess.run();
        }

        @Override
        public void close() {
        }
    }

    private void fail(Throwable t) {
        if (failure.compareAndSet(null, t)) {
            LOG.log(Level.SEVERE, symbol + ": fatal bus error; stopping", t);
            running = false;
            onFatal.accept(t);
        }
    }

    // ---- inspection -------------------------------------------------------------------------------------------------

    public String symbol() {
        return symbol;
    }

    /** The first unrecoverable error, or null. */
    public Throwable failure() {
        return failure.get();
    }

    /** Sequence the engine resumed after at startup (-1 for a fresh start). */
    public long restoredSeq() {
        return restoredSeq;
    }

    /** Highest seq whose events record the broker has acknowledged. */
    public long ackedSeq() {
        return ackedSeq.get();
    }

    @Override
    public void close() {
        running = false;
        if (consumer != null) {
            consumer.wakeup();
        }
        if (ingress != null) {
            try {
                ingress.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (pipeline != null) {
            pipeline.close();
        }
        producer.flush();
    }
}

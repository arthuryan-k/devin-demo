package orderbook.bus;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;

import orderbook.Symbols;
import orderbook.exchange.ExchangeCore;
import orderbook.journal.Journal;
import orderbook.services.Instrument;
import orderbook.services.ReferenceDataClient;

/**
 * The standalone matching-engine service: one {@link SymbolBus} per hosted symbol, each with its own journal
 * directory ({@code <journalRoot>/<SYMBOL>/}), sharing one idempotent producer. Creates its topics (one partition
 * each) on startup. Crash-only: any bus or journal failure exits the process, and a restart recovers.
 *
 * <p>Configuration (environment variable, else system property, else default):
 * {@code KAFKA_BOOTSTRAP_SERVERS} / {@code bus.bootstrap} ({@code localhost:9092}),
 * {@code SYMBOLS} / {@code bus.symbols} (else the active listing of {@code REFDATA_URL} / {@code refdata.url},
 * the reference-data service; else {@link Symbols#ALL}),
 * {@code JOURNAL_PATH} / {@code journal.path} ({@code journal}),
 * {@code SNAPSHOT_EVERY} / {@code journal.snapshotEvery} ({@code 10000}),
 * {@code TOPIC_REPLICATION} / {@code bus.replication} ({@code 1}).
 */
public final class EngineService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EngineService.class.getName());

    private final Producer<String, String> producer;
    private final List<Journal> journals = new ArrayList<>();
    private final List<SymbolBus> buses = new ArrayList<>();

    private EngineService(String bootstrap, Collection<String> symbols, Path journalRoot, Journal.Options options,
            int snapshotEvery, Consumer<Throwable> onFatal) {
        producer = new KafkaProducer<>(producerProps(bootstrap));
        try {
            for (String symbol : symbols) {
                Journal journal = new Journal(ExchangeCore.journalDirectory(journalRoot, symbol), options);
                journals.add(journal);
                SymbolBus.Config defaults = SymbolBus.Config.defaults(symbol, bootstrap, journal);
                buses.add(new SymbolBus(new SymbolBus.Config(symbol, bootstrap, journal, snapshotEvery,
                        defaults.marketDataSnapshotEvery(), defaults.inputRingSize(), defaults.outputRingSize()),
                        producer, onFatal));
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /** Creates the topics for {@code symbols} if needed, then recovers and starts every symbol. */
    public static EngineService start(String bootstrap, Collection<String> symbols, Path journalRoot,
            Journal.Options options, int snapshotEvery, int replication, Consumer<Throwable> onFatal) {
        symbols.forEach(Symbols::requireValid);
        createTopics(bootstrap, symbols, replication);
        return new EngineService(bootstrap, symbols, journalRoot, options, snapshotEvery, onFatal);
    }

    public List<SymbolBus> buses() {
        return List.copyOf(buses);
    }

    public SymbolBus bus(String symbol) {
        return buses.stream().filter(b -> b.symbol().equals(symbol)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not hosted: " + symbol));
    }

    /** Creates {@code commands/events/marketdata/dlq} topics with one partition; fails if one exists with more. */
    public static void createTopics(String bootstrap, Collection<String> symbols, int replication) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        try (Admin admin = Admin.create(p)) {
            List<NewTopic> topics = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String symbol : symbols) {
                Map<String, String> forever = Map.of("retention.ms", "-1", "retention.bytes", "-1");
                topics.add(new NewTopic(Topics.commands(symbol), 1, (short) replication).configs(forever));
                topics.add(new NewTopic(Topics.events(symbol), 1, (short) replication).configs(forever));
                topics.add(new NewTopic(Topics.marketData(symbol), 1, (short) replication)
                        .configs(Map.of("retention.ms", "86400000")));
                topics.add(new NewTopic(Topics.deadLetter(symbol), 1, (short) replication));
            }
            topics.forEach(t -> names.add(t.name()));
            admin.createTopics(topics).values().forEach((name, future) -> {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) {
                        throw new IllegalStateException("failed to create " + name, e.getCause());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            for (TopicDescription d : admin.describeTopics(names).allTopicNames().get().values()) {
                if (d.partitions().size() != 1) {
                    throw new IllegalStateException(d.name() + " has " + d.partitions().size()
                            + " partitions; per-symbol ordering needs exactly 1");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("failed to describe topics", e.getCause());
        }
    }

    static Properties producerProps(String bootstrap) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        p.put(ProducerConfig.LINGER_MS_CONFIG, "1");
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return p;
    }

    /** Stops consuming, drains the pipelines, flushes the producer, then closes the journals. */
    @Override
    public void close() {
        buses.forEach(SymbolBus::close);
        producer.close();
        for (Journal journal : journals) {
            try {
                journal.close();
            } catch (IOException e) {
                LOG.warning("failed to close journal " + journal.directory() + ": " + e);
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        String bootstrap = setting("KAFKA_BOOTSTRAP_SERVERS", "bus.bootstrap", "localhost:9092");
        List<String> symbols = hostedSymbols();
        Path journalRoot = Path.of(setting("JOURNAL_PATH", "journal.path", "journal"));
        int snapshotEvery = Integer.parseInt(setting("SNAPSHOT_EVERY", "journal.snapshotEvery", "10000"));
        int replication = Integer.parseInt(setting("TOPIC_REPLICATION", "bus.replication", "1"));
        EngineService service = start(bootstrap, symbols, journalRoot, Journal.Options.FLUSH, snapshotEvery,
                replication, t -> new Thread(() -> System.exit(1), "fatal-exit").start());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            service.close();
            stopped.countDown();
        }, "shutdown"));
        LOG.info("matching-engine on " + bootstrap + " for " + symbols + ", journal " + journalRoot.toAbsolutePath());
        stopped.await();
    }

    private static List<String> hostedSymbols() {
        String explicit = setting("SYMBOLS", "bus.symbols", null);
        if (explicit != null) {
            return Arrays.stream(explicit.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        String refdata = setting("REFDATA_URL", "refdata.url", null);
        if (refdata == null) {
            return Symbols.ALL;
        }
        try {
            return new ReferenceDataClient(URI.create(refdata)).symbols(30, Duration.ofSeconds(1)).stream()
                    .map(Instrument::symbol).toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot load the listing from " + refdata, e);
        }
    }

    private static String setting(String env, String property, String fallback) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) {
            v = System.getProperty(property);
        }
        return v == null || v.isBlank() ? fallback : v;
    }
}

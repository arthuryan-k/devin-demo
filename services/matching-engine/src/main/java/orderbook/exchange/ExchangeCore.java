package orderbook.exchange;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import orderbook.Command;
import orderbook.MatchingEngine;
import orderbook.Symbols;
import orderbook.journal.Journal;
import orderbook.pipeline.DisruptorPipeline;
import orderbook.pipeline.Gateway;
import orderbook.pipeline.JournalHandler;
import orderbook.pipeline.Pipeline;
import orderbook.pipeline.Result;

/**
 * One exchange process hosting a separate {@link SymbolEngine} per listed symbol (IBKR-style: one engine process,
 * one book per instrument). Each symbol has its own input ring, engine thread, output ring and journal directory
 * ({@code <journalRoot>/<SYMBOL>/}), so symbols never share a sequence, a book or a lock; the only shared state is the
 * order-id counter, which keeps order ids unique exchange-wide. Every book starts empty, with no liquidity and no
 * reference price.
 *
 * <p>{@link #submit} routes a command by {@link Command#symbol()}; an unlisted symbol is rejected with
 * {@code UNKNOWN_SYMBOL} before it reaches any ring.
 */
public final class ExchangeCore implements AutoCloseable {

    public static final String UNKNOWN_SYMBOL = "UNKNOWN_SYMBOL";

    private final Map<String, SymbolEngine> engines;
    private final AtomicLong orderIds = new AtomicLong(1);

    private ExchangeCore(Builder b) {
        Map<String, SymbolEngine> map = new LinkedHashMap<>();
        try {
            for (String symbol : b.symbols) {
                Symbols.requireValid(symbol);
                if (map.containsKey(symbol)) {
                    throw new IllegalArgumentException("duplicate symbol " + symbol);
                }
                Pipeline pipeline = b.pipelines.apply(symbol);
                Journal journal = null;
                if (b.journalRoot != null) {
                    journal = new Journal(journalDirectory(b.journalRoot, symbol), b.journalOptions);
                    pipeline.addConsumer(new JournalHandler(journal));
                    pipeline.setSnapshotInterval(b.snapshotInterval);
                }
                map.put(symbol, new SymbolEngine(symbol, pipeline, new Gateway(pipeline, orderIds), journal));
            }
        } catch (RuntimeException e) {
            map.values().forEach(SymbolEngine::close);
            throw e;
        }
        engines = java.util.Collections.unmodifiableMap(map);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Every listed symbol on Disruptor pipelines, without journals. */
    public static ExchangeCore create() {
        return builder().build();
    }

    /** The journal directory of {@code symbol} under {@code journalRoot}. */
    public static Path journalDirectory(Path journalRoot, String symbol) {
        return journalRoot.resolve(Symbols.requireValid(symbol));
    }

    /** Rebuilds {@code symbol}'s engine from its own journal directory alone; other symbols are not read. */
    public static MatchingEngine recover(Path journalRoot, String symbol) {
        try (Journal journal = new Journal(journalDirectory(journalRoot, symbol))) {
            return journal.load().toEngine(symbol, orderbook.CommandLog.NONE);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Hosted symbols, in listing order. */
    public List<String> symbols() {
        return List.copyOf(engines.keySet());
    }

    public Map<String, SymbolEngine> engines() {
        return engines;
    }

    public boolean hosts(String symbol) {
        return symbol != null && engines.containsKey(symbol);
    }

    /** @throws IllegalArgumentException if {@code symbol} is not hosted */
    public SymbolEngine engine(String symbol) {
        SymbolEngine engine = symbol == null ? null : engines.get(symbol);
        if (engine == null) {
            throw new IllegalArgumentException("unknown symbol '" + symbol + "', hosted: " + engines.keySet());
        }
        return engine;
    }

    /** A fresh order id, unique across every symbol. */
    public long nextOrderId() {
        return orderIds.getAndIncrement();
    }

    /**
     * Routes {@code command} to its symbol's gateway. The returned sequence is that symbol's pipeline sequence; collect
     * an awaited response with {@link #awaitResponse(String, long, long)}.
     */
    public Gateway.Submission submit(long participantId, Command command, boolean awaitResponse) {
        Objects.requireNonNull(command, "command");
        SymbolEngine engine = engines.get(command.symbol());
        if (engine == null) {
            return new Gateway.Submission(Pipeline.BUSY, null, new Gateway.Rejection(Gateway.orderId(command),
                    UNKNOWN_SYMBOL, "unknown symbol '" + command.symbol() + "'"));
        }
        return engine.gateway().submit(participantId, command, awaitResponse);
    }

    public Result awaitResponse(String symbol, long seq, long timeoutMillis) {
        return engine(symbol).pipeline().awaitResponse(seq, timeoutMillis);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (SymbolEngine engine : engines.values()) {
            try {
                engine.close();
            } catch (RuntimeException e) {
                failure = failure == null ? e : failure;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public static final class Builder {
        private Collection<String> symbols = Symbols.ALL;
        private Function<String, Pipeline> pipelines = DisruptorPipeline::new;
        private Path journalRoot;
        private Journal.Options journalOptions = Journal.Options.FLUSH;
        private int snapshotInterval;

        private Builder() {
        }

        /** Symbols to host (well-formed codes, e.g. from the reference-data service); defaults to {@link Symbols#ALL}. */
        public Builder symbols(Collection<String> symbols) {
            this.symbols = List.copyOf(symbols);
            return this;
        }

        /** Builds each symbol's pipeline; defaults to a {@link DisruptorPipeline} per symbol. */
        public Builder pipelines(Function<String, Pipeline> factory) {
            this.pipelines = Objects.requireNonNull(factory, "factory");
            return this;
        }

        /** Journal every symbol into {@code root/<SYMBOL>/}; null (default) disables journaling. */
        public Builder journalRoot(Path root) {
            this.journalRoot = root;
            return this;
        }

        public Builder journalOptions(Journal.Options options) {
            this.journalOptions = Objects.requireNonNull(options, "options");
            return this;
        }

        /** Snapshot each journaled symbol every {@code commands} commands; 0 (default) disables. */
        public Builder snapshotInterval(int commands) {
            this.snapshotInterval = commands;
            return this;
        }

        public ExchangeCore build() {
            return new ExchangeCore(this);
        }
    }
}

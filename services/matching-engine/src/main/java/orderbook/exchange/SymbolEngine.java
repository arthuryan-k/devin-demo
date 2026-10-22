package orderbook.exchange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

import orderbook.journal.Journal;
import orderbook.pipeline.Gateway;
import orderbook.pipeline.Pipeline;

/**
 * Everything one symbol owns inside an {@link ExchangeCore}: its pipeline (input ring, single engine thread running a
 * {@link orderbook.MatchingEngine} for that symbol, output ring), the {@link Gateway} in front of it and, optionally,
 * its own {@link Journal} directory. Nothing here is shared with other symbols except the order-id counter.
 */
public final class SymbolEngine implements AutoCloseable {

    private final String symbol;
    private final Pipeline pipeline;
    private final Gateway gateway;
    private final Journal journal;

    SymbolEngine(String symbol, Pipeline pipeline, Gateway gateway, Journal journal) {
        this.symbol = Objects.requireNonNull(symbol, "symbol");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.journal = journal;
        if (!symbol.equals(pipeline.symbol())) {
            throw new IllegalArgumentException("pipeline for " + pipeline.symbol() + " used for " + symbol);
        }
    }

    public String symbol() {
        return symbol;
    }

    public Pipeline pipeline() {
        return pipeline;
    }

    public Gateway gateway() {
        return gateway;
    }

    /** This symbol's journal, or null if the core runs without journals. */
    public Journal journal() {
        return journal;
    }

    /** Drains and stops the pipeline, then closes the journal. */
    @Override
    public void close() {
        pipeline.close();
        if (journal != null) {
            try {
                journal.close();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to close journal for " + symbol, e);
            }
        }
    }
}

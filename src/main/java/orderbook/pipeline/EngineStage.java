package orderbook.pipeline;

import java.util.List;

import orderbook.MatchingEngine;

/** The single matching stage: copies an input slot into an output slot and runs the engine. No I/O. */
final class EngineStage {

    private MatchingEngine engine = new MatchingEngine();

    void process(CommandEvent in, ResultEvent out) {
        out.seq = in.seq;
        out.participantId = in.participantId;
        out.command = in.command;
        out.reset = in.reset;
        out.awaited = in.awaited;
        if (in.reset) {
            engine = new MatchingEngine();
            out.events = List.of();
        } else {
            out.events = engine.process(in.command);
        }
        in.command = null;
    }

    /** Engine-thread state; only safe to read once the pipeline is quiescent (tests). */
    MatchingEngine engine() {
        return engine;
    }
}

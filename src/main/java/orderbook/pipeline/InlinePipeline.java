package orderbook.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import orderbook.Command;

/**
 * Single-threaded {@link Pipeline} that runs every stage inline on the publishing thread. Used where there are no
 * threads (the browser build) and as a reference implementation; same sequencing and fan-out order semantics.
 */
public final class InlinePipeline implements Pipeline {

    private final EngineStage engine = new EngineStage();
    private final CommandEvent in = new CommandEvent();
    private final ResultEvent out = new ResultEvent();
    private final List<ResultHandler> consumers = new ArrayList<>();
    private final Map<Long, Result> responses = new HashMap<>();
    private long next;

    @Override
    public long tryPublish(long participantId, Command command, boolean awaitResponse) {
        Objects.requireNonNull(command, "command");
        return run(participantId, command, false, false, awaitResponse);
    }

    @Override
    public long tryPublishReset() {
        return run(0, null, true, false, false);
    }

    @Override
    public long tryPublishSnapshot() {
        return run(0, null, false, true, false);
    }

    @Override
    public void setSnapshotInterval(int commands) {
        engine.setSnapshotInterval(commands);
    }

    private long run(long participantId, Command command, boolean reset, boolean snapshotMarker,
            boolean awaitResponse) {
        long seq = next++;
        in.set(seq, participantId, command, reset, snapshotMarker, awaitResponse);
        engine.process(in, out);
        if (awaitResponse) {
            responses.put(seq, out.toResult());
        }
        for (ResultHandler consumer : consumers) {
            try {
                consumer.onResult(out, true);
            } catch (Exception e) {
                throw new IllegalStateException("output consumer failed at seq " + seq, e);
            }
        }
        return seq;
    }

    @Override
    public Result awaitResponse(long seq, long timeoutMillis) {
        Result result = responses.remove(seq);
        if (result == null) {
            throw new IllegalStateException("no awaited result for seq " + seq);
        }
        return result;
    }

    @Override
    public void addConsumer(ResultHandler handler) {
        consumers.add(Objects.requireNonNull(handler, "handler"));
    }

    @Override
    public long lastSequence() {
        return next - 1;
    }

    @Override
    public void close() {
    }
}

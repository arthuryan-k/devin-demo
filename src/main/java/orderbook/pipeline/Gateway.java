package orderbook.pipeline;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import orderbook.Command;
import orderbook.Event;

/**
 * Front door of the {@link Pipeline}: runs a participant's pre-trade {@link RiskCheck} (e.g. account reservations)
 * and then publishes without blocking. A full input ring becomes an {@link Event.OrderRejected.Reason#BUSY}
 * rejection, and the risk check is told to release whatever it reserved. Also hands out order ids.
 */
public final class Gateway {

    /** Pre-trade check for one participant; called on the submitting thread before the command is published. */
    public interface RiskCheck {

        /** Returns null to admit, or a rejection that stops the command before it is published. */
        Rejection admit(Command command);

        /** The admitted command was not published (ring full); undo what {@link #admit} reserved. */
        void unpublished(Command command, Event.OrderRejected busy);
    }

    public record Rejection(long orderId, String reason, String message) {
    }

    /**
     * Outcome of {@link #submit}: the global sequence, or {@link Pipeline#BUSY} with {@link #busy()} set, or a risk
     * {@link #rejection()}.
     */
    public record Submission(long seq, Event.OrderRejected busy, Rejection rejection) {

        public boolean published() {
            return seq >= 0;
        }
    }

    private final Pipeline pipeline;
    private final Map<Long, RiskCheck> riskChecks = new ConcurrentHashMap<>();
    private final AtomicLong nextOrderId = new AtomicLong(1);

    public Gateway(Pipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    public Pipeline pipeline() {
        return pipeline;
    }

    public void setRiskCheck(long participantId, RiskCheck check) {
        if (check == null) {
            riskChecks.remove(participantId);
        } else {
            riskChecks.put(participantId, check);
        }
    }

    /** A fresh order id, unique across every producer. */
    public long nextOrderId() {
        return nextOrderId.getAndIncrement();
    }

    /** Makes sure ids chosen elsewhere (e.g. by a client) are never handed out again. */
    public void reserveOrderIdsThrough(long orderId) {
        nextOrderId.accumulateAndGet(orderId + 1, Math::max);
    }

    public Submission submit(long participantId, Command command, boolean awaitResponse) {
        Objects.requireNonNull(command, "command");
        if (command instanceof Command.Place place) {
            reserveOrderIdsThrough(place.order().id());
        }
        RiskCheck check = riskChecks.get(participantId);
        if (check != null) {
            Rejection rejection = check.admit(command);
            if (rejection != null) {
                return new Submission(Pipeline.BUSY, null, rejection);
            }
        }
        long seq = pipeline.tryPublish(participantId, command, awaitResponse);
        if (seq != Pipeline.BUSY) {
            return new Submission(seq, null, null);
        }
        Event.OrderRejected busy = busy(command);
        if (check != null) {
            check.unpublished(command, busy);
        }
        return new Submission(Pipeline.BUSY, busy, null);
    }

    public static Event.OrderRejected busy(Command command) {
        return new Event.OrderRejected(orderId(command), Event.OrderRejected.Reason.BUSY);
    }

    public static long orderId(Command command) {
        if (command instanceof Command.Place place) {
            return place.order().id();
        }
        if (command instanceof Command.Cancel cancel) {
            return cancel.orderId();
        }
        return ((Command.Amend) command).orderId();
    }
}

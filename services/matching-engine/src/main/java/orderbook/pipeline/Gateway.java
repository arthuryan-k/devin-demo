package orderbook.pipeline;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import orderbook.Command;
import orderbook.Event;

/**
 * Front door of the {@link Pipeline}: publishes without blocking, turning a full input ring into an
 * {@link Event.OrderRejected.Reason#BUSY} rejection, and hands out order ids. Pre-trade risk (cash and share
 * reservations) is not done here: the external gateway calls the risk service before a command reaches the engine.
 */
public final class Gateway {

    /** A command stopped before it was published (e.g. routing or ownership checks in front of the gateway). */
    public record Rejection(long orderId, String reason, String message) {
    }

    /**
     * Outcome of {@link #submit}: the global sequence, or {@link Pipeline#BUSY} with {@link #busy()} set, or a
     * {@link #rejection()}.
     */
    public record Submission(long seq, Event.OrderRejected busy, Rejection rejection) {

        public boolean published() {
            return seq >= 0;
        }
    }

    private final Pipeline pipeline;
    private final AtomicLong nextOrderId;

    public Gateway(Pipeline pipeline) {
        this(pipeline, new AtomicLong(1));
    }

    /** {@code orderIds} holds the next id to hand out; gateways sharing it hand out ids unique across all of them. */
    public Gateway(Pipeline pipeline, AtomicLong orderIds) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.nextOrderId = Objects.requireNonNull(orderIds, "orderIds");
    }

    public String symbol() {
        return pipeline.symbol();
    }

    public Pipeline pipeline() {
        return pipeline;
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
        long seq = pipeline.tryPublish(participantId, command, awaitResponse);
        if (seq != Pipeline.BUSY) {
            return new Submission(seq, null, null);
        }
        return new Submission(Pipeline.BUSY, busy(command), null);
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

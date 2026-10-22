package orderbook.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Per-participant admission control in front of the input ring: a token bucket on every order/cancel/amend message,
 * a cap on resting orders, and a violation counter that blocks a participant once it reaches
 * {@link Limits#maxViolations()}.
 */
public final class AdmissionControl {

    /** Message rate (per second) and burst per participant, resting-order cap, and violations before blocking. */
    public record Limits(double messagesPerSec, int burst, int maxOpenOrders, int maxViolations) {

        /** Sized so a simulated participant at the maximum simulation rate never hits them. */
        public static final Limits DEFAULT = new Limits(200, 400, 100, 100);

        public Limits {
            if (!(messagesPerSec > 0) || burst < 1 || maxOpenOrders < 1 || maxViolations < 1) {
                throw new IllegalArgumentException("limits must be positive");
            }
        }
    }

    public enum Violation {
        RATE_LIMITED, MAX_OPEN_ORDERS
    }

    private final Limits limits;
    private final LongSupplier nanoClock;
    private final Map<Long, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> violations = new ConcurrentHashMap<>();

    public AdmissionControl(Limits limits, LongSupplier nanoClock) {
        this.limits = limits;
        this.nanoClock = nanoClock;
    }

    public Limits limits() {
        return limits;
    }

    public boolean isBlocked(long participantId) {
        return violations(participantId) >= limits.maxViolations();
    }

    public int violations(long participantId) {
        AtomicInteger n = violations.get(participantId);
        return n == null ? 0 : n.get();
    }

    /**
     * Admits one message, or returns the violation (and counts it). {@code openOrders} is the participant's current
     * number of resting orders; {@code adds} is true for a new order.
     */
    public Violation admit(long participantId, boolean adds, int openOrders) {
        TokenBucket bucket = buckets.computeIfAbsent(participantId,
                id -> new TokenBucket(limits.messagesPerSec(), limits.burst(), nanoClock));
        Violation violation = null;
        if (!bucket.tryAcquire()) {
            violation = Violation.RATE_LIMITED;
        } else if (adds && openOrders >= limits.maxOpenOrders()) {
            violation = Violation.MAX_OPEN_ORDERS;
        }
        if (violation != null) {
            violations.computeIfAbsent(participantId, id -> new AtomicInteger()).incrementAndGet();
        }
        return violation;
    }

    /** Forgets buckets, violations and blocks (e.g. on a market reset). */
    public void reset() {
        buckets.clear();
        violations.clear();
    }
}

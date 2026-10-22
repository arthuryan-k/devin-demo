package orderbook.api;

import java.util.List;
import java.util.Random;

import orderbook.Command;
import orderbook.Event;
import orderbook.Order;
import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.pipeline.Gateway;
import orderbook.sim.ExchangeClient.Credentials;
import orderbook.sim.Simulator;

/**
 * The participant-facing API gateway shared by every transport (Jetty, the in-page browser build, in-process
 * clients). Authenticates the API key, applies {@link AdmissionControl} (rate limit, open-order cap, blocking) and
 * only then publishes to the input ring through {@link Gateway#submit}; nothing refused here reaches the engine.
 */
public final class ExchangeService {

    public static final long RESPONSE_TIMEOUT_MILLIS = 10_000;
    /** Pre-trade rejection of a cancel/amend aimed at another participant's order (same reason the account uses). */
    public static final String NOT_OWN_OPEN_ORDER = "NOT_OWN_OPEN_ORDER";

    /** A published (or pre-trade rejected) command: its events once the engine has processed it. */
    public record Outcome(long participantId, long orderId, long seq, List<Event> events,
            Gateway.Rejection rejection) {
    }

    private final Simulator simulator;
    private final ParticipantRegistry registry;
    private final AdmissionControl admission;

    public ExchangeService(Simulator simulator, AdmissionControl.Limits limits, Random keyRandom) {
        this.simulator = simulator;
        this.registry = new ParticipantRegistry(keyRandom);
        this.admission = new AdmissionControl(limits, System::nanoTime);
    }

    public ParticipantRegistry registry() {
        return registry;
    }

    public AdmissionControl admission() {
        return admission;
    }

    public String userKey() {
        return registry.userKey();
    }

    public Credentials register(String name) {
        return registry.register(name);
    }

    public String label(long participantId) {
        return registry.label(participantId);
    }

    /** Resolves the caller: 401 without a known key, 403 once blocked for repeated violations. */
    public Credentials authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(401, "MISSING_API_KEY", "X-Api-Key header is required");
        }
        Credentials who = registry.resolve(apiKey)
                .orElseThrow(() -> new ApiException(401, "INVALID_API_KEY", "unknown API key"));
        if (admission.isBlocked(who.participantId())) {
            throw new ApiException(403, "BLOCKED", who.label() + " is blocked after "
                    + admission.limits().maxViolations() + " limit violations");
        }
        return who;
    }

    public Outcome place(Credentials who, Side side, OrderType type, TimeInForce timeInForce, long price, long qty,
            Long requestedId) {
        Gateway gateway = simulator.gateway();
        long id;
        if (requestedId != null) {
            id = requestedId;
            gateway.reserveOrderIdsThrough(id);
        } else {
            id = gateway.nextOrderId();
        }
        long pid = who.participantId();
        Order order = type == OrderType.LIMIT ? Order.limit(id, pid, side, price, qty, timeInForce)
                : Order.market(id, pid, side, qty, timeInForce);
        return submit(who, new Command.Place(order));
    }

    public Outcome cancel(Credentials who, long orderId) {
        return submit(who, new Command.Cancel(orderId));
    }

    public Outcome amend(Credentials who, long orderId, Long newPrice, Long newQty) {
        return submit(who, new Command.Amend(orderId, newPrice, newQty));
    }

    /**
     * Admission and publication happen atomically on the simulator thread (the open-order count comes from its
     * replica); the engine's response is awaited without holding that thread.
     */
    private Outcome submit(Credentials who, Command command) {
        long pid = who.participantId();
        long orderId = Gateway.orderId(command);
        Gateway.Submission submission = simulator.execute(() -> {
            simulator.catchUp();
            AdmissionControl.Violation violation = admission.admit(pid, command instanceof Command.Place,
                    simulator.ownOrders(pid).size());
            if (violation == AdmissionControl.Violation.RATE_LIMITED) {
                throw new ApiException(429, violation.name(), "rate limit of " + admission.limits().messagesPerSec()
                        + " msgs/sec (burst " + admission.limits().burst() + ") exceeded");
            }
            if (violation == AdmissionControl.Violation.MAX_OPEN_ORDERS) {
                throw new ApiException(429, violation.name(),
                        "at most " + admission.limits().maxOpenOrders() + " open orders per participant");
            }
            if (!(command instanceof Command.Place)) {
                long owner = simulator.engine().book().find(orderId).map(Order::participantId)
                        .orElse(Order.NO_PARTICIPANT);
                if (owner != Order.NO_PARTICIPANT && owner != pid) {
                    return new Gateway.Submission(-1, null, new Gateway.Rejection(orderId, NOT_OWN_OPEN_ORDER,
                            "order " + orderId + " is not one of your open orders"));
                }
            }
            return simulator.gateway().submit(pid, command, true);
        });
        if (submission.rejection() != null) {
            return new Outcome(pid, orderId, -1, List.of(), submission.rejection());
        }
        long seq = submission.seq();
        if (submission.busy() != null) {
            return new Outcome(pid, orderId, seq, List.of(submission.busy()), null);
        }
        List<Event> events = simulator.pipeline().awaitResponse(seq, RESPONSE_TIMEOUT_MILLIS).events();
        simulator.run(() -> simulator.awaitApplied(seq));
        return new Outcome(pid, orderId, seq, events, null);
    }
}

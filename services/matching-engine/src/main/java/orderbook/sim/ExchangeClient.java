package orderbook.sim;

import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;

/**
 * How a simulated participant reaches the exchange: the same public API an external client uses (register for an API
 * key, then authenticated order/cancel/amend calls). Implementations: over HTTP, or in-process through the same API
 * gateway where there is no network (the browser build, deterministic tests). Prices are in ticks.
 */
public interface ExchangeClient {

    record Credentials(long participantId, String label, String apiKey) {
    }

    /** Outcome of one call: the HTTP status, the order id the exchange used (or -1) and a reason on failure. */
    record Reply(int status, long orderId, String reason) {

        public boolean ok() {
            return status == 200;
        }
    }

    /** {@code POST /participants/register}. */
    Credentials register(String name);

    /** {@code POST /api/orders}; {@code price} is ignored for market orders. */
    Reply place(String apiKey, Side side, OrderType type, long price, long qty, TimeInForce timeInForce);

    /** {@code DELETE /api/orders/{id}}. */
    Reply cancel(String apiKey, long orderId);

    /** {@code PATCH /api/orders/{id}}; a null field is left unchanged. */
    Reply amend(String apiKey, long orderId, Long newPrice, Long newQty);
}

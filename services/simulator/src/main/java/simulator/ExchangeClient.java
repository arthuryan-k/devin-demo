package simulator;

/**
 * How a simulated participant reaches the exchange: exactly what an external client does. It signs up for a real
 * account through the auth service, then trades with that account's API key over the public gateway API. Prices are
 * in ticks; the HTTP implementation converts to decimals at the edge.
 */
public interface ExchangeClient {

    /** A real account: its participant id, display label and API key. */
    record Credentials(long participantId, String label, String apiKey) {
    }

    /**
     * Outcome of one call: the HTTP status, the order id (or -1), how much traded immediately, how much rests on the
     * book afterwards, and a reason on failure.
     */
    record Reply(int status, long orderId, long filledQty, long restingQty, String reason) {

        public static Reply failed(int status, String reason) {
            return new Reply(status, -1, 0, 0, reason);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Signs up a new account named {@code name} (unique per call) and returns its credentials. */
    Credentials register(String name);

    /** {@code POST /api/orders}; {@code price} is ignored for market orders. */
    Reply place(String apiKey, String symbol, Side side, OrderType type, long price, long qty, TimeInForce timeInForce);

    /** {@code DELETE /api/orders/{id}}. */
    Reply cancel(String apiKey, String symbol, long orderId);

    /** {@code PATCH /api/orders/{id}}; a null field is left unchanged. */
    Reply amend(String apiKey, String symbol, long orderId, Long newPrice, Long newQty);
}

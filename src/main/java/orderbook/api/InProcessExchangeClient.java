package orderbook.api;

import java.util.function.Supplier;

import orderbook.OrderType;
import orderbook.Side;
import orderbook.TimeInForce;
import orderbook.sim.ExchangeClient;

/** {@link ExchangeClient} that calls the {@link ExchangeService} directly: same auth and limits, no network. */
public final class InProcessExchangeClient implements ExchangeClient {

    private final ExchangeService service;

    public InProcessExchangeClient(ExchangeService service) {
        this.service = service;
    }

    @Override
    public Credentials register(String name) {
        return service.register(name);
    }

    @Override
    public Reply place(String apiKey, Side side, OrderType type, long price, long qty, TimeInForce timeInForce) {
        return call(() -> service.place(service.authenticate(apiKey), side, type, timeInForce,
                type == OrderType.LIMIT ? price : 0, qty, null));
    }

    @Override
    public Reply cancel(String apiKey, long orderId) {
        return call(() -> service.cancel(service.authenticate(apiKey), orderId));
    }

    @Override
    public Reply amend(String apiKey, long orderId, Long newPrice, Long newQty) {
        return call(() -> service.amend(service.authenticate(apiKey), orderId, newPrice, newQty));
    }

    private static Reply call(Supplier<ExchangeService.Outcome> call) {
        try {
            return new Reply(200, call.get().orderId(), null);
        } catch (ApiException e) {
            return new Reply(e.status(), -1, e.reason());
        }
    }
}

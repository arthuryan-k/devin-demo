package orderbook;

/** A fill between a resting (maker) order and an incoming (taker) order, executed at the maker's price. */
public record Trade(long makerOrderId, long takerOrderId, long price, long qty) {
}

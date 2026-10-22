package simulator;

/** One of a participant's own resting orders, as its client last saw it. Prices in ticks. */
public record OwnOrder(long id, Side side, long price, long qtyRemaining) {
}

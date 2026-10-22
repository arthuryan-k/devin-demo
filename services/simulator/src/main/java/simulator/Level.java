package simulator;

/** One aggregated price level of a book side, in ticks. */
public record Level(long price, long totalQty, int orderCount) {
}

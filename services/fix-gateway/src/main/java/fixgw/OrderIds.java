package fixgw;

/**
 * Same order-id layout as the REST gateway (services/gateway/src/ids.ts): ms since 2026-01-01, 4-bit instance,
 * 8-bit counter. The FIX gateway takes its own instance number (default 15) so ids never collide with REST ones.
 */
public class OrderIds {
    private static final long EPOCH_MS = 1767225600000L; // 2026-01-01T00:00:00Z
    private final int instance;
    private long lastMs;
    private int counter;

    public OrderIds(int instance) {
        if (instance < 0 || instance > 15) throw new IllegalArgumentException("instance must be in [0, 15]");
        this.instance = instance;
    }

    public synchronized long next() {
        long now = Math.max(System.currentTimeMillis() - EPOCH_MS, 0);
        if (now > lastMs) {
            lastMs = now;
            counter = 0;
        } else if (++counter >= 256) {
            lastMs++;
            counter = 0;
        }
        return ((lastMs << 4) + instance) * 256 + counter;
    }
}

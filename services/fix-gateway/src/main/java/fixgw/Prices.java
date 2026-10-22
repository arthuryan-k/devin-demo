package fixgw;

import java.math.BigDecimal;

/** Decimal FIX prices <-> integer ticks (0.01). */
public class Prices {
    public static final int SCALE = 2;

    public static long toTicks(String px) {
        BigDecimal d = new BigDecimal(px);
        return d.movePointRight(SCALE).longValueExact();
    }

    public static String toPx(long ticks) {
        return BigDecimal.valueOf(ticks, SCALE).toPlainString();
    }
}

package orderbook.demo;

import java.math.BigDecimal;

/** Decimal &harr; integer-tick conversion for the HTTP layer. One tick is 0.01; cash amounts use the same unit. */
final class Ticks {

    static final int SCALE = 2;

    private Ticks() {
    }

    static long parse(String name, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        try {
            return new BigDecimal(text.trim()).movePointRight(SCALE).longValueExact();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a decimal number");
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(name + " must be a multiple of 0.01");
        }
    }

    static String format(long ticks) {
        return BigDecimal.valueOf(ticks, SCALE).toPlainString();
    }
}

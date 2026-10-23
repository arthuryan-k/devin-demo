package fixgw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PricesTest {

    @Test
    void convertsDecimalPricesToTicks() {
        assertEquals(10050, Prices.toTicks("100.50"));
        assertEquals(10050, Prices.toTicks("100.5"));
        assertEquals(10000, Prices.toTicks("100"));
        assertEquals(1, Prices.toTicks("0.01"));
    }

    @Test
    void rejectsSubTickPrices() {
        assertThrows(ArithmeticException.class, () -> Prices.toTicks("100.005"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(NumberFormatException.class, () -> Prices.toTicks("1O0.00"));
    }

    @Test
    void formatsTicksWithTwoDecimals() {
        assertEquals("100.50", Prices.toPx(10050));
        assertEquals("0.01", Prices.toPx(1));
        assertEquals("0.00", Prices.toPx(0));
    }

    @Test
    void roundTrips() {
        for (long ticks : new long[] {1, 99, 100, 12345, 99999999}) {
            assertEquals(ticks, Prices.toTicks(Prices.toPx(ticks)));
        }
    }
}

package com.example.marketplace;

import com.example.marketplace.shared.Rules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class RulesTest {
    @Test
    void moneyIsExact() {
        assertEquals(12345, Rules.minor(new BigDecimal("123.45")));
        assertThrows(ArithmeticException.class, () -> Rules.minor(new BigDecimal("1.001")));
    }

    @Test
    void discountAllocationNeverOverchargesSmallLastLine() {
        for (int subtotal = 1; subtotal <= 100; subtotal++)
            for (int total = 0; total <= subtotal; total++) {
                long prior = 0;
                for (int line = 1; line <= subtotal; line++) {
                    long cumulative = Rules.allocatedNet(line, total, subtotal);
                    long net = cumulative - prior;
                    assertTrue(net >= 0 && net <= 1);
                    prior = cumulative;
                }
                assertEquals(total, prior);
            }
    }

    @Test
    void roundingAndDiscountBounds() {
        assertEquals(1, Rules.discount(5, 10));
        assertEquals(0, Rules.discount(0, 100));
        assertThrows(Exception.class, () -> Rules.discount(100, 101));
        assertThrows(Exception.class, () -> Rules.minor(new BigDecimal("-1")));
    }
}

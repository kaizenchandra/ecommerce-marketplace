package com.example.marketplace.shared;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Rules {
    private Rules() {
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public static long minor(BigDecimal amount) {
        require(
                amount != null && amount.signum() >= 0 && amount.compareTo(new BigDecimal("10000000")) <= 0,
                "Invalid price");
        return amount.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    public static long allocatedNet(long cumulativeGross, long total, long subtotal) {
        require(
                subtotal > 0
                        && total >= 0
                        && total <= subtotal
                        && cumulativeGross >= 0
                        && cumulativeGross <= subtotal,
                "Invalid allocation");
        return BigDecimal.valueOf(cumulativeGross)
                .multiply(BigDecimal.valueOf(total))
                .divide(BigDecimal.valueOf(subtotal), 0, RoundingMode.DOWN)
                .longValueExact();
    }

    public static long discount(long subtotal, int percent) {
        require(subtotal >= 0 && percent >= 0 && percent <= 100, "Invalid discount");
        return BigDecimal.valueOf(subtotal)
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}

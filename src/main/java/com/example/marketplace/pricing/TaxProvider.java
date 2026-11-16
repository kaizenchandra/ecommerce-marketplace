package com.example.marketplace.pricing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface TaxProvider {
    Calculation calculate(
            Map<String, Object> origin, Map<String, Object> destination, List<Line> lines, long shipping);

    record Line(UUID offerId, long net, String taxCode) {
    }

    record Calculation(
            Map<UUID, Long> lineTax, long shippingTax, String reference, Instant expiresAt) {
    }
}

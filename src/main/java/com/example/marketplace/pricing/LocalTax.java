package com.example.marketplace.pricing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        name = "marketplace.tax.provider",
        havingValue = "local",
        matchIfMissing = true)
public class LocalTax implements TaxProvider {
    public Calculation calculate(
            Map<String, Object> origin,
            Map<String, Object> destination,
            List<Line> lines,
            long shipping) {
        Map<UUID, Long> taxes = new HashMap<>();
        lines.forEach(line -> taxes.put(line.offerId(), 0L));
        return new Calculation(taxes, 0, "local-tax-exempt-fixtures", Instant.now().plusSeconds(300));
    }
}

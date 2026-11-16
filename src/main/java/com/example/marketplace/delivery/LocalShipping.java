package com.example.marketplace.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@ConditionalOnProperty(
        name = "marketplace.shipping.provider",
        havingValue = "local",
        matchIfMissing = true)
public class LocalShipping implements ShippingProvider {
    public Rate quote(
            Map<String, Object> origin, Map<String, Object> destination, Map<String, Object> parcel) {
        return new Rate(0, "local-free", Map.of("provider", "local"));
    }

    public Tracking track(String carrier, String tracking) {
        return new Tracking("TRANSIT", null);
    }
}

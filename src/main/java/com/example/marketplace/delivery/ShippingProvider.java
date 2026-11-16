package com.example.marketplace.delivery;

import java.time.Instant;
import java.util.Map;

public interface ShippingProvider {
    Rate quote(
            Map<String, Object> origin, Map<String, Object> destination, Map<String, Object> parcel);

    Tracking track(String carrier, String tracking);

    record Rate(long amount, String reference, Map<String, Object> details) {
    }

    record Tracking(String status, Instant occurredAt) {
    }
}

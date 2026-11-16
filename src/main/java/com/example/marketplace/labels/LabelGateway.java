package com.example.marketplace.labels;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LabelGateway {
    Result purchase(UUID operation, String rate);

    Result retrieve(String id);

    Optional<Result> reconcile(UUID operation, String rate, Instant since);

    record Result(
            String id,
            String status,
            String rateId,
            String operation,
            String carrier,
            String tracking,
            String labelUrl) {
    }
}

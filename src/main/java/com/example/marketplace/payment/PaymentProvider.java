package com.example.marketplace.payment;

import java.util.Optional;

public interface PaymentProvider {
    Result capture(String key, long amount);

    Result lookup(String key);

    Optional<Result> find(String key);

    /**
     * Expire unpaid hosted sessions; return a concurrent successful capture for compensation.
     */
    default Optional<Result> cancel(String key) {
        return find(key);
    }

    String refund(String paymentKey, String refundKey, long amount);

    record Result(String reference, String status, long amount, long refunded, String checkoutUrl) {
        public Result(String reference, String status, long amount, long refunded) {
            this(reference, status, amount, refunded, null);
        }
    }
}

package com.example.marketplace.payment;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Deterministic capture-only simulator. Never receives card data. Separate durable provider ledger.
 */
@Service
@org.springframework.context.annotation.Profile("local")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "marketplace.payment.provider",
        havingValue = "local",
        matchIfMissing = true)
public class LocalPaymentProvider implements PaymentProvider {
    private final Store s;

    public LocalPaymentProvider(Store s) {
        this.s = s;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result capture(String key, long amount) {
        s.db.update(
                "insert into provider_payments(operation_key,amount,status,reference) values"
                        + " (?,?,'CAPTURED',?) on conflict do nothing",
                key,
                amount,
                UUID.randomUUID());
        Result result = lookup(key);
        Rules.require(result.amount() == amount, "Provider key amount mismatch");
        return result;
    }

    public java.util.Optional<Result> find(String key) {
        if (s.db.queryForObject(
                "select count(*) from provider_payments where operation_key=?", Integer.class, key)
                == 0) return java.util.Optional.empty();
        return java.util.Optional.of(lookup(key));
    }

    public Result lookup(String key) {
        var r = s.db.queryForMap("select * from provider_payments where operation_key=?", key);
        return new Result(
                r.get("reference").toString(),
                r.get("status").toString(),
                ((Number) r.get("amount")).longValue(),
                ((Number) r.get("refunded")).longValue());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String refund(String paymentKey, String refundKey, long amount) {
        s.db.queryForMap(
                "select * from provider_payments where operation_key=? for update", paymentKey);
        var prior =
                s.db.queryForList("select * from provider_refunds where operation_key=?", refundKey);
        if (!prior.isEmpty()) {
            Rules.require(
                    prior.getFirst().get("payment_key").equals(paymentKey)
                            && ((Number) prior.getFirst().get("amount")).longValue() == amount,
                    "Refund key reused");
            return prior.getFirst().get("reference").toString();
        }
        Rules.require(
                s.db.update(
                        "update provider_payments set refunded=refunded+? where operation_key=? and"
                                + " amount-refunded>=?",
                        amount,
                        paymentKey,
                        amount)
                        == 1,
                "Provider refund limit");
        UUID ref = UUID.randomUUID();
        s.db.update(
                "insert into provider_refunds values (?,?,?,?)", refundKey, paymentKey, amount, ref);
        return ref.toString();
    }
}

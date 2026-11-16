package com.example.marketplace.checkout;

import com.example.marketplace.payment.PaymentProvider;
import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;

@Service
public class Workflow {
    private final Store s;
    private final PaymentProvider provider;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final com.example.marketplace.events.EventService events;
    private final com.example.marketplace.reporting.TaxReporting taxReporting;

    public Workflow(
            Store s,
            PaymentProvider provider,
            PlatformTransactionManager manager,
            MeterRegistry metrics,
            com.example.marketplace.events.EventService events,
            com.example.marketplace.reporting.TaxReporting taxReporting) {
        this.s = s;
        this.provider = provider;
        this.tx = new TransactionTemplate(manager);
        this.metrics = metrics;
        this.events = events;
        this.taxReporting = taxReporting;
    }

    @Scheduled(
            fixedDelayString = "${marketplace.worker-delay:1000}",
            initialDelayString = "${marketplace.worker-initial-delay:1000}")
    public void tick() {
        for (var row :
                s.db.queryForList(
                        "select order_id from checkout_workflows where status='READY' and next_attempt<=now()"
                                + " order by next_attempt limit 16")) {
            UUID id = (UUID) row.get("order_id");
            try {
                advance(id);
            } catch (Exception e) {
                metrics.counter("marketplace.saga.failure").increment();
                s.db.update(
                        "update checkout_workflows set last_error=?,updated_at=now() where order_id=?",
                        e.getClass().getSimpleName(),
                        id);
                LoggerFactory.getLogger(getClass())
                        .warn("Checkout workflow {} failed: {}", id, e.getClass().getSimpleName());
            }
        }
        for (var row :
                s.db.queryForList(
                        "select id from refunds where status='PENDING' and next_attempt<=now() order by"
                                + " next_attempt limit 16")) {
            try {
                refund((UUID) row.get("id"));
            } catch (Exception e) {
                metrics.counter("marketplace.refund.failure").increment();
            }
        }
        for (var event :
                s.db.queryForList(
                        "update provider_webhook_inbox set attempts=attempts+1,next_attempt=now()+interval '30"
                                + " seconds' where (provider,event_id) in (select provider,event_id from"
                                + " provider_webhook_inbox where status='READY' and next_attempt<=now() and"
                                + " attempts<8 order by next_attempt limit 16 for update skip locked) returning"
                                + " provider,event_id,order_id")) {
            try {
                UUID order = (UUID) event.get("order_id");
                provider.find(order.toString()).ifPresent(result -> complete(order, result));
                s.db.update(
                        "update provider_webhook_inbox set status='DONE' where provider=? and event_id=?",
                        event.get("provider"),
                        event.get("event_id"));
            } catch (Exception e) {
                metrics.counter("marketplace.webhook.recovery.failure").increment();
            }
        }
        s.db.update(
                "update provider_webhook_inbox set status='MANUAL_REVIEW' where status='READY' and"
                        + " attempts>=8 and next_attempt<=now()");
    }

    public void advance(UUID id) {
        // Claim via short transaction and a lease. Duplicate execution after lease expiry is safe.
        var claim =
                tx.execute(
                        t -> {
                            var workflow =
                                    s.db.queryForMap(
                                            "select * from checkout_workflows where order_id=? for update", id);
                            if (!"READY".equals(workflow.get("status"))
                                    || ((java.sql.Timestamp) workflow.get("next_attempt"))
                                    .toInstant()
                                    .isAfter(java.time.Instant.now())) return null;
                            int attempts = ((Number) workflow.get("attempts")).intValue();
                            if (attempts >= 8) {
                                s.db.update(
                                        "update checkout_workflows set status='MANUAL_REVIEW' where order_id=?", id);
                                return null;
                            }
                            s.db.update(
                                    "update checkout_workflows set attempts=attempts+1,next_attempt=now()+interval"
                                            + " '30 seconds',updated_at=now() where order_id=?",
                                    id);
                            return s.db.queryForMap(
                                    "select p.*,o.status order_status,o.expires_at<now() expired from payments p join"
                                            + " orders o on o.id=p.order_id where o.id=?",
                                    id);
                        });
        if (claim == null) return;
        if (Boolean.TRUE.equals(claim.get("expired"))) tx.executeWithoutResult(t -> cancelLocked(id));
        // Even cancelled pending orders must reconcile an in-flight capture before declaring DONE.
        PaymentProvider.Result result;
        if ("CANCELLED"
                .equals(s.db.queryForObject("select status from orders where id=?", String.class, id))) {
            var found = provider.cancel(id.toString());
            if (found.isEmpty()) {
                tx.executeWithoutResult(
                        t -> s.db.update("update checkout_workflows set status='DONE' where order_id=?", id));
                return;
            }
            result = found.get();
        } else result = provider.capture(id.toString(), ((Number) claim.get("amount")).longValue());
        complete(id, result);
    }

    public void complete(UUID id, PaymentProvider.Result result) {
        tx.executeWithoutResult(
                t -> {
                    var order = s.db.queryForMap("select * from orders where id=? for update", id);
                    var payment = s.db.queryForMap("select * from payments where order_id=? for update", id);
                    Rules.require(
                            result.amount() == ((Number) payment.get("amount")).longValue(),
                            "Provider amount mismatch");
                    if ("PENDING".equals(result.status())) {
                        s.db.update(
                                "update payments set provider_ref=?,checkout_url=? where id=? and status='CREATED'",
                                result.reference(),
                                result.checkoutUrl(),
                                payment.get("id"));
                        s.db.update(
                                "update checkout_workflows set attempts=0,next_attempt=now()+interval '30"
                                        + " seconds',updated_at=now() where order_id=? and status<>'MANUAL_REVIEW'",
                                id);
                        return;
                    }
                    if ("FAILED".equals(result.status())) {
                        if (!"CREATED".equals(payment.get("status"))) return;
                        if ("PAYMENT_PENDING".equals(order.get("status"))) cancelLocked(id);
                        s.db.update(
                                "update payments set status='FAILED',provider_ref=? where id=? and"
                                        + " status='CREATED'",
                                result.reference(),
                                payment.get("id"));
                        s.db.update(
                                "update checkout_workflows set status='DONE',updated_at=now() where order_id=?",
                                id);
                        s.event(id, "PaymentFailed");
                        return;
                    }
                    if (!"CAPTURED".equals(result.status()))
                        throw new IllegalStateException("Unresolved provider state");
                    if ("CREATED".equals(payment.get("status"))) {
                        s.db.update(
                                "update payments set status='CAPTURED',provider_ref=?,version=version+1 where id=?",
                                result.reference(),
                                payment.get("id"));
                        s.db.update(
                                "insert into payment_attempts(id,payment_id,operation,provider_ref,outcome) values"
                                        + " (?,?,'CAPTURE',?,'CAPTURED')",
                                UUID.randomUUID(),
                                payment.get("id"),
                                result.reference());
                    }
                    taxReporting.enqueueSale(id);
                    String status = order.get("status").toString();
                    if ("PAYMENT_PENDING".equals(status)
                            && ((java.sql.Timestamp) order.get("expires_at"))
                            .toInstant()
                            .isBefore(java.time.Instant.now())) {
                        cancelLocked(id);
                        status = "CANCELLED";
                    }
                    if ("PAYMENT_PENDING".equals(status)) {
                        inventory(id, true);
                        s.db.update("update orders set status='CONFIRMED',version=version+1 where id=?", id);
                        s.event(id, "OrderConfirmed");
                        metrics.counter("marketplace.checkout.confirmed").increment();
                    } else if ("CANCELLED".equals(status))
                        queueRefund(
                                (UUID) payment.get("id"),
                                null,
                                "cancel:" + id,
                                ((Number) payment.get("amount")).longValue());
                    s.db.update(
                            "update checkout_workflows set status='DONE',updated_at=now() where order_id=?", id);
                });
    }

    public void inventory(UUID order, boolean commit) {
        for (var r :
                s.db.queryForList(
                        "select * from inventory_reservations where order_id=? and status='ACTIVE' order by"
                                + " offer_id for update",
                        order)) {
            int q = ((Number) r.get("quantity")).intValue();
            s.db.update(
                    "update inventory set reserved=reserved-?,on_hand=on_hand-?,version=version+1 where"
                            + " offer_id=?",
                    q,
                    commit ? q : 0,
                    r.get("offer_id"));
            s.db.update(
                    "update inventory_reservations set status=? where order_id=? and offer_id=?",
                    commit ? "COMMITTED" : "RELEASED",
                    order,
                    r.get("offer_id"));
            if (commit)
                s.db.update(
                        "insert into inventory_movements(id,offer_id,delta,reason) values"
                                + " (?,?,?,'ORDER_COMMIT')",
                        UUID.randomUUID(),
                        r.get("offer_id"),
                        -q);
        }
    }

    public void cancelLocked(UUID id) {
        var order = s.db.queryForMap("select * from orders where id=? for update", id);
        if ("CANCELLED".equals(order.get("status"))) return;
        Rules.require(
                Set.of("PAYMENT_PENDING", "CONFIRMED").contains(order.get("status")),
                "Order cannot be cancelled after shipping");
        Rules.require(
                s.db.queryForObject(
                        "select count(*) from shipments sh join seller_orders so on"
                                + " so.id=sh.seller_order_id where so.order_id=?",
                        Integer.class,
                        id)
                        == 0,
                "Already shipped");
        Rules.require(
                s.db.queryForObject(
                        "select coalesce(sum(label_reserved_quantity),0) from order_items where order_id=?",
                        Integer.class,
                        id)
                        == 0,
                "Label fulfillment must be resolved/aborted before cancellation");
        if ("PAYMENT_PENDING".equals(order.get("status"))) inventory(id, false);
        else {
            for (var i :
                    s.db.queryForList("select * from order_items where order_id=? order by offer_id", id)) {
                s.db.update(
                        "update inventory set on_hand=on_hand+?,version=version+1 where offer_id=?",
                        i.get("quantity"),
                        i.get("offer_id"));
                s.db.update(
                        "insert into inventory_movements(id,offer_id,delta,reason) values"
                                + " (?,?,?,'CANCEL_RESTOCK')",
                        UUID.randomUUID(),
                        i.get("offer_id"),
                        i.get("quantity"));
            }
        }
        s.db.update("update orders set status='CANCELLED',version=version+1 where id=?", id);
        var payment = s.db.queryForMap("select * from payments where order_id=? for update", id);
        if ("CAPTURED".equals(payment.get("status")))
            queueRefund(
                    (UUID) payment.get("id"),
                    null,
                    "cancel:" + id,
                    ((Number) payment.get("amount")).longValue());
        s.event(id, "OrderCancelled");
    }

    public void queueRefund(UUID payment, UUID returnId, String key, long amount) {
        // Call only while holding the order lock. Reserve refundable amount, including pending refunds.
        var paid = s.db.queryForMap("select * from payments where id=? for update", payment);
        if (s.db.queryForObject(
                "select count(*) from refunds where operation_key=?", Integer.class, key)
                > 0) return;
        Rules.require(
                Set.of("CAPTURED", "PARTIALLY_REFUNDED").contains(paid.get("status")),
                "Captured payment required for refund");
        long eligible =
                s.db.queryForObject(
                        "select amount-coalesce((select sum(amount) from refunds where payment_id=p.id),0) from"
                                + " payments p where id=?",
                        Long.class,
                        payment);
        Rules.require(amount >= 0 && amount <= eligible, "Refund exceeds remaining captured amount");
        s.db.update(
                "insert into refunds(id,payment_id,return_id,operation_key,amount) values (?,?,?,?,?)",
                UUID.randomUUID(),
                payment,
                returnId,
                key,
                amount);
    }

    public void refund(UUID id) {
        var r =
                tx.execute(
                        t -> {
                            var row =
                                    s.db.queryForMap(
                                            "select r.*,p.order_id from refunds r join payments p on p.id=r.payment_id"
                                                    + " where r.id=? for update of r",
                                            id);
                            if (!"PENDING".equals(row.get("status"))
                                    || ((java.sql.Timestamp) row.get("next_attempt"))
                                    .toInstant()
                                    .isAfter(java.time.Instant.now())) return null;
                            if (((Number) row.get("attempts")).intValue() >= 8) {
                                s.db.update("update refunds set status='MANUAL_REVIEW' where id=?", id);
                                return null;
                            }
                            s.db.update(
                                    "update refunds set attempts=attempts+1,next_attempt=now()+interval '30 seconds'"
                                            + " where id=?",
                                    id);
                            return row;
                        });
        if (r == null) return;
        String ref =
                provider.refund(
                        r.get("order_id").toString(),
                        r.get("operation_key").toString(),
                        ((Number) r.get("amount")).longValue());
        tx.executeWithoutResult(
                t -> {
                    s.db.queryForMap("select * from orders where id=? for update", r.get("order_id"));
                    s.db.queryForMap("select * from payments where id=? for update", r.get("payment_id"));
                    if (s.db.update(
                            "update refunds set status='COMPLETED',provider_ref=? where id=? and"
                                    + " status='PENDING'",
                            ref,
                            id)
                            == 0) return;
                    s.db.update(
                            "update payments set refunded=refunded+?,status=case when refunded+?=amount then"
                                    + " 'REFUNDED' else 'PARTIALLY_REFUNDED' end,version=version+1 where id=?",
                            r.get("amount"),
                            r.get("amount"),
                            r.get("payment_id"));
                    if (r.get("return_id") != null)
                        s.db.update("update returns set status='REFUNDED' where id=?", r.get("return_id"));
                    taxReporting.enqueueReversals(id);
                    s.event((UUID) r.get("order_id"), "RefundCompleted");
                });
    }

    public void relay() {
        events.relay();
    }

    @Scheduled(fixedDelay = 60000, initialDelayString = "${marketplace.worker-initial-delay:1000}")
    public void reconcile() {
        // Provider-independent polling: claim bounded due rows fairly across replicas.
        for (var r :
                s.db.queryForList(
                        "update payments set reconcile_after=now()+interval '60 seconds' where id in (select"
                                + " p.id from payments p join orders o on o.id=p.order_id where"
                                + " p.reconcile_after<=now() and (p.status='CREATED' or (o.status='CANCELLED' and"
                                + " p.status='CAPTURED' and not exists(select 1 from refunds r where"
                                + " r.payment_id=p.id))) order by p.reconcile_after limit 100 for update of p skip"
                                + " locked) returning order_id")) {
            UUID id = (UUID) r.get("order_id");
            try {
                boolean cancelled =
                        "CANCELLED"
                                .equals(
                                        s.db.queryForObject("select status from orders where id=?", String.class, id));
                var found = cancelled ? provider.cancel(id.toString()) : provider.find(id.toString());
                found.ifPresent(result -> complete(id, result));
            } catch (Exception e) {
                metrics.counter("marketplace.reconciliation.failure").increment();
            }
        }
    }
}

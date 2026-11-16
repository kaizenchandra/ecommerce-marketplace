package com.example.marketplace.labels;

import com.example.marketplace.delivery.ShippingProvider;
import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LabelService {
    private final Store s;
    private final ShippingProvider shipping;
    private final LabelGateway gateway;
    private final TransactionTemplate tx;

    public LabelService(
            Store s,
            ShippingProvider shipping,
            LabelGateway gateway,
            PlatformTransactionManager manager) {
        this.s = s;
        this.shipping = shipping;
        this.gateway = gateway;
        tx = new TransactionTemplate(manager);
    }

    private JsonNode json(Object raw) {
        try {
            return s.json.readTree(raw.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid label request snapshot");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(JsonNode node) {
        return s.json.convertValue(node, Map.class);
    }

    @Transactional
    public Map<String, Object> create(UUID group, UUID item, int quantity, long ceiling, String key) {
        Rules.require(
                quantity > 0
                        && quantity <= 100
                        && ceiling >= 0
                        && ceiling <= 1000000000
                        && !key.isBlank()
                        && key.length() <= 100,
                "Invalid label purchase");
        var sellerOrder = s.db.queryForMap("select * from seller_orders where id=?", group);
        s.seller((UUID) sellerOrder.get("seller_id"));
        var order =
                s.db.queryForMap("select * from orders where id=? for update", sellerOrder.get("order_id"));
        var existing =
                s.db.queryForList(
                        "select * from shipping_labels where seller_order_id=? and idempotency_key=?",
                        group,
                        key);
        if (!existing.isEmpty()) {
            var prior = existing.getFirst();
            Rules.require(
                    item.equals(prior.get("order_item_id"))
                            && quantity == ((Number) prior.get("quantity")).intValue()
                            && ceiling == ((Number) prior.get("max_cost")).longValue(),
                    "Label key reused with different payload");
            return prior;
        }
        Rules.require(
                Set.of("CONFIRMED", "PARTIALLY_FULFILLED").contains(order.get("status")),
                "Order is not eligible for label fulfillment");
        var line =
                s.db.queryForMap("select * from order_items where id=? and seller_order_id=?", item, group);
        JsonNode snapshot = json(line.get("shipping_data"));
        Rules.require(
                snapshot.has("origin") && snapshot.has("destination") && snapshot.has("parcel"),
                "Legacy order lacks immutable shipping snapshot");
        Map<String, Object> parcel = map(snapshot.path("parcel"));
        long grams =
                Math.multiplyExact(Long.parseLong(snapshot.path("weightGrams").asText("0")), quantity);
        if (!snapshot.path("origin").isEmpty()) {
            Rules.require(
                    grams > 0 && grams <= snapshot.path("maxWeightGrams").asLong(),
                    "Quantity exceeds immutable parcel packing limit");
            parcel.put("weight", Long.toString(grams));
        }
        Rules.require(
                s.db.update(
                        "update order_items set label_reserved_quantity=label_reserved_quantity+? where"
                                + " id=? and shipped_quantity+label_reserved_quantity+?<=quantity",
                        quantity,
                        item,
                        quantity)
                        == 1,
                "Label quantity already shipped or reserved");
        UUID id = UUID.randomUUID();
        var request =
                Map.of(
                        "origin",
                        snapshot.path("origin"),
                        "destination",
                        snapshot.path("destination"),
                        "parcel",
                        parcel);
        s.db.update(
                "insert into"
                        + " shipping_labels(id,seller_order_id,order_item_id,quantity,idempotency_key,max_cost,request)"
                        + " values (?,?,?,?,?,?,?::jsonb)",
                id,
                group,
                item,
                quantity,
                key,
                ceiling,
                s.json(request));
        return s.db.queryForMap("select * from shipping_labels where id=?", id);
    }

    public Map<String, Object> owned(UUID id) {
        var row =
                s.db.queryForMap(
                        "select l.*,so.seller_id,so.order_id from shipping_labels l join seller_orders so on"
                                + " so.id=l.seller_order_id where l.id=?",
                        id);
        s.seller((UUID) row.get("seller_id"));
        return row;
    }

    @Scheduled(
            fixedDelay = 1000,
            initialDelayString = "${marketplace.worker-initial-delay:1000}",
            scheduler = "reportingScheduler")
    public void poll() {
        for (var row :
                s.db.queryForList(
                        "select id from shipping_labels where status in ('READY','ATTEMPTED') and"
                                + " next_attempt<=now() order by next_attempt limit 8"))
            try {
                process((UUID) row.get("id"));
            } catch (Exception e) {
                s.db.update(
                        "update shipping_labels set last_error=? where id=?",
                        e.getClass().getSimpleName(),
                        row.get("id"));
            }
    }

    public void process(UUID id) {
        UUID lease = UUID.randomUUID();
        var row =
                tx.execute(
                        t -> {
                            var job = s.db.queryForMap("select *,next_attempt<=clock_timestamp() as due from shipping_labels where id=? for update", id);
                            if (!Set.of("READY", "ATTEMPTED").contains(job.get("status"))
                                    || !Boolean.TRUE.equals(job.get("due"))) return null;
                            if (((Number) job.get("attempts")).intValue() >= 8) {
                                s.db.update("update shipping_labels set status='MANUAL_REVIEW' where id=?", id);
                                return null;
                            }
                            s.db.update(
                                    "update shipping_labels set"
                                            + " attempts=attempts+1,lease_token=?,next_attempt=now()+interval '30"
                                            + " seconds' where id=?",
                                    lease,
                                    id);
                            return job;
                        });
        if (row == null) return;
        LabelGateway.Result result;
        if (row.get("provider_id") != null)
            result = gateway.retrieve(row.get("provider_id").toString());
        else if (row.get("purchase_started_at") != null) {
            var found =
                    gateway.reconcile(
                            id,
                            row.get("rate_id").toString(),
                            ((java.sql.Timestamp) row.get("purchase_started_at")).toInstant());
            if (found.isEmpty()) return;
            result = found.get();
        } else {
            if (row.get("rate_id") == null) {
                var request = json(row.get("request"));
                var rate =
                        shipping.quote(
                                map(request.path("origin")),
                                map(request.path("destination")),
                                map(request.path("parcel")));
                Rules.require(
                        rate.amount() >= 0 && rate.amount() <= ((Number) row.get("max_cost")).longValue(),
                        "Label rate exceeds authorized ceiling");
                if (s.db.update(
                        "update shipping_labels set rate_id=?,rate_amount=? where id=? and status='READY'"
                                + " and lease_token=?",
                        rate.reference(),
                        rate.amount(),
                        id,
                        lease)
                        != 1) return;
                row.put("rate_id", rate.reference());
            }
            // Durable one-shot claim committed BEFORE the non-idempotent remote purchase. Never reset
            // this marker.
            if (s.db.update(
                    "update shipping_labels set status='ATTEMPTED',purchase_started_at=now() where id=?"
                            + " and status='READY' and purchase_started_at is null and lease_token=? and"
                            + " next_attempt>now()",
                    id,
                    lease)
                    != 1) return;
            result = gateway.purchase(id, row.get("rate_id").toString());
        }
        apply(id, result);
    }

    public void apply(UUID id, LabelGateway.Result result) {
        var row = s.db.queryForMap("select * from shipping_labels where id=?", id);
        Rules.require(
                row.get("purchase_started_at") != null
                        && (row.get("provider_id") == null || result.id().equals(row.get("provider_id")))
                        && result.operation().equals("marketplace-label:" + id)
                        && result.rateId().equals(row.get("rate_id")),
                "Label operation/rate mismatch");
        s.db.update(
                "update shipping_labels set provider_id=? where id=? and provider_id is null",
                result.id(),
                id);
        if (!"SUCCESS".equals(result.status())) {
            if (Set.of("ERROR", "REFUNDED", "REFUNDPENDING", "REFUNDREJECTED").contains(result.status()))
                s.db.update(
                        "update shipping_labels set status='MANUAL_REVIEW',last_error='ProviderTerminalState'"
                                + " where id=?",
                        id);
            return;
        }
        Rules.require(
                result.carrier() != null
                        && result.carrier().matches("[a-z0-9_]{1,100}")
                        && result.tracking() != null
                        && result.tracking().matches("[A-Za-z0-9_-]{1,150}")
                        && result.labelUrl() != null
                        && !result.labelUrl().isBlank(),
                "Incomplete label transaction");
        tx.executeWithoutResult(
                t -> {
                    var group =
                            s.db.queryForMap(
                                    "select so.order_id from shipping_labels l join seller_orders so on"
                                            + " so.id=l.seller_order_id where l.id=?",
                                    id);
                    s.db.queryForMap("select * from orders where id=? for update", group.get("order_id"));
                    if (s.db.update(
                            "update shipping_labels set"
                                    + " status='PURCHASED',carrier=?,tracking=?,label_url=?,last_error=null where"
                                    + " id=? and status in ('ATTEMPTED','MANUAL_REVIEW')",
                            result.carrier(),
                            result.tracking(),
                            result.labelUrl(),
                            id)
                            == 1) s.event((UUID) group.get("order_id"), "ShippingLabelPurchased");
                });
    }

    @Transactional
    public Object dispatch(UUID id) {
        var row = owned(id);
        UUID order = (UUID) row.get("order_id");
        var state = s.db.queryForMap("select * from orders where id=? for update", order);
        row = s.db.queryForMap("select * from shipping_labels where id=? for update", id);
        if ("DISPATCHED".equals(row.get("status")))
            return s.db.queryForMap("select * from shipments where label_id=?", id);
        Rules.require(
                "PURCHASED".equals(row.get("status"))
                        && Set.of("CONFIRMED", "PARTIALLY_FULFILLED").contains(state.get("status")),
                "Purchased label and active order required");
        s.db.update(
                "update order_items set"
                        + " label_reserved_quantity=label_reserved_quantity-?,shipped_quantity=shipped_quantity+?"
                        + " where id=?",
                row.get("quantity"),
                row.get("quantity"),
                row.get("order_item_id"));
        UUID shipment = UUID.randomUUID();
        s.db.update(
                "insert into"
                        + " shipments(id,seller_order_id,order_item_id,carrier,tracking,status,quantity,idempotency_key,label_id)"
                        + " values (?,?,?,?,?,'SHIPPED',?,?,?)",
                shipment,
                row.get("seller_order_id"),
                row.get("order_item_id"),
                row.get("carrier"),
                row.get("tracking"),
                row.get("quantity"),
                "label:" + id,
                id);
        s.db.update("update shipping_labels set status='DISPATCHED' where id=?", id);
        s.db.update(
                "update orders set status='PARTIALLY_FULFILLED',version=version+1 where id=?", order);
        s.event(order, "ShipmentCreated");
        return s.db.queryForMap("select * from shipments where id=?", shipment);
    }

    @Transactional
    public void abort(UUID id) {
        var row = owned(id);
        abortUnattempted(id, row);
    }

    @Transactional
    public void abortAdmin(UUID id) {
        s.role("ADMIN");
        var row =
                s.db.queryForMap(
                        "select l.*,so.order_id from shipping_labels l join seller_orders so on"
                                + " so.id=l.seller_order_id where l.id=?",
                        id);
        abortUnattempted(id, row);
    }

    private void abortUnattempted(UUID id, Map<String, Object> row) {
        s.db.queryForMap("select * from orders where id=? for update", row.get("order_id"));
        row = s.db.queryForMap("select * from shipping_labels where id=? for update", id);
        if ("ABORTED".equals(row.get("status"))) return;
        Rules.require(
                row.get("purchase_started_at") == null,
                "An attempted/purchased label requires provider resolution; do not release ambiguous"
                        + " quantity");
        Rules.require(
                Set.of("READY", "MANUAL_REVIEW").contains(row.get("status")), "Label cannot be aborted");
        s.db.update("update shipping_labels set status='ABORTED' where id=?", id);
        s.db.update(
                "update order_items set label_reserved_quantity=label_reserved_quantity-? where id=?",
                row.get("quantity"),
                row.get("order_item_id"));
    }

    public void releaseClosed(UUID id) {
        s.role("ADMIN");
        var row =
                s.db.queryForMap(
                        "select l.*,so.order_id from shipping_labels l join seller_orders so on"
                                + " so.id=l.seller_order_id where l.id=?",
                        id);
        Rules.require(row.get("provider_id") != null, "Provider reference required to resolve label");
        var evidence = gateway.retrieve(row.get("provider_id").toString());
        Rules.require(
                evidence.id().equals(row.get("provider_id"))
                        && evidence.operation().equals("marketplace-label:" + id)
                        && evidence.rateId().equals(row.get("rate_id"))
                        && Set.of("ERROR", "REFUNDED").contains(evidence.status()),
                "Authoritative failed/refunded label required");
        tx.executeWithoutResult(
                t -> {
                    s.db.queryForMap("select * from orders where id=? for update", row.get("order_id"));
                    var current = s.db.queryForMap("select * from shipping_labels where id=? for update", id);
                    if ("ABORTED".equals(current.get("status"))) return;
                    Rules.require(
                            !"DISPATCHED".equals(current.get("status")),
                            "Dispatched quantity cannot be released");
                    s.db.update(
                            "update shipping_labels set status='ABORTED',last_error=? where id=?",
                            evidence.status(),
                            id);
                    s.db.update(
                            "update order_items set label_reserved_quantity=label_reserved_quantity-? where id=?",
                            current.get("quantity"),
                            current.get("order_item_id"));
                    s.audit("LABEL_PROVIDER_CLOSED", id);
                });
    }

    public Object reconcileKnown(UUID id, String providerId) {
        var result = gateway.retrieve(providerId);
        apply(id, result);
        return s.db.queryForMap("select * from shipping_labels where id=?", id);
    }

    public Object refresh(UUID id) {
        var row = owned(id);
        Rules.require(row.get("provider_id") != null, "Provider label not yet known");
        var result = gateway.retrieve(row.get("provider_id").toString());
        Rules.require(
                row.get("purchase_started_at") != null
                        && (row.get("provider_id") == null || result.id().equals(row.get("provider_id")))
                        && result.operation().equals("marketplace-label:" + id)
                        && result.rateId().equals(row.get("rate_id")),
                "Label lookup mismatch");
        s.db.update("update shipping_labels set label_url=? where id=?", result.labelUrl(), id);
        return owned(id);
    }
}

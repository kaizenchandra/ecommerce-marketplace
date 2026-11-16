package com.example.marketplace.fulfillment;

import com.example.marketplace.checkout.Workflow;
import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class FulfillmentApi {
    private final Store s;
    private final Workflow workflow;
    private final DeliveryService delivery;
    private final String shippingProvider;

    public FulfillmentApi(
            Store s,
            Workflow workflow,
            DeliveryService delivery,
            @Value("${marketplace.shipping.provider:local}") String shippingProvider) {
        this.s = s;
        this.workflow = workflow;
        this.delivery = delivery;
        this.shippingProvider = shippingProvider;
    }

    public static long unitAllocation(long amount, int units, int total) {
        Rules.require(
                amount >= 0 && units >= 0 && units <= total && total > 0, "Invalid unit allocation");
        return java.math.BigDecimal.valueOf(amount)
                .multiply(java.math.BigDecimal.valueOf(units))
                .divide(java.math.BigDecimal.valueOf(total), 0, java.math.RoundingMode.DOWN)
                .longValueExact();
    }

    @PostMapping("/orders/{id}/cancel")
    @Transactional
    Object cancel(@PathVariable UUID id) {
        s.ownedOrder(id);
        workflow.cancelLocked(id);
        return s.ownedOrder(id);
    }

    @GetMapping("/seller/orders")
    Object sellerOrders() {
        s.role("SELLER");
        return s.db.queryForList(
                "select so.*,o.status,i.id"
                        + " item_id,i.title,i.quantity,i.shipped_quantity,i.delivered_quantity,i.label_reserved_quantity"
                        + " from seller_orders so join sellers se on se.id=so.seller_id join orders o on"
                        + " o.id=so.order_id join order_items i on i.seller_order_id=so.id where se.owner_id=?"
                        + " order by o.created_at desc limit 200",
                s.user());
    }

    private String key(String key, UUID item, Integer quantity) {
        if (key == null) {
            Rules.require(quantity == null, "Idempotency-Key required for quantity requests");
            return "legacy:" + item;
        }
        Rules.require(!key.isBlank() && key.length() <= 100, "Invalid idempotency key");
        return key;
    }

    @PostMapping("/seller-orders/{id}/shipments")
    @Transactional
    Object ship(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Ship request) {
        var group = s.db.queryForMap("select * from seller_orders where id=?", id);
        s.seller((UUID) group.get("seller_id"));
        var order =
                s.db.queryForMap("select * from orders where id=? for update", group.get("order_id"));
        String scope = key(key, request.itemId(), request.quantity());
        var prior =
                s.db.queryForList(
                        "select * from shipments where seller_order_id=? and idempotency_key=?", id, scope);
        if (!prior.isEmpty()) {
            var p = prior.getFirst();
            Rules.require(
                    p.get("order_item_id").equals(request.itemId())
                            && p.get("carrier").equals(request.carrier())
                            && p.get("tracking").equals(request.tracking())
                            && (request.quantity() == null
                            || ((Number) p.get("quantity")).intValue() == request.quantity()),
                    "Shipment key reused with different payload");
            return p;
        }
        Rules.require(
                Set.of("CONFIRMED", "PARTIALLY_FULFILLED").contains(order.get("status")),
                "Order is not shippable");
        var item =
                s.db.queryForMap(
                        "select * from order_items where id=? and seller_order_id=?", request.itemId(), id);
        int q =
                request.quantity() == null
                        ? ((Number) item.get("quantity")).intValue()
                        - ((Number) item.get("shipped_quantity")).intValue()
                        : request.quantity();
        Rules.require(
                q > 0
                        && s.db.update(
                        "update order_items set shipped_quantity=shipped_quantity+? where id=? and"
                                + " shipped_quantity+?+label_reserved_quantity<=quantity",
                        q,
                        request.itemId(),
                        q)
                        == 1,
                "Shipment exceeds remaining quantity");
        UUID shipment = UUID.randomUUID();
        s.db.update(
                "insert into"
                        + " shipments(id,seller_order_id,order_item_id,carrier,tracking,status,quantity,idempotency_key)"
                        + " values (?,?,?,?,?,'SHIPPED',?,?)",
                shipment,
                id,
                request.itemId(),
                request.carrier(),
                request.tracking(),
                q,
                scope);
        s.db.update(
                "update orders set status='PARTIALLY_FULFILLED',version=version+1 where id=?",
                group.get("order_id"));
        s.event((UUID) group.get("order_id"), "ShipmentCreated");
        return s.db.queryForMap("select * from shipments where id=?", shipment);
    }

    @GetMapping("/orders/{id}/shipments")
    Object shipments(@PathVariable UUID id) {
        s.ownedOrder(id);
        return s.db.queryForList(
                "select sh.* from shipments sh join seller_orders so on so.id=sh.seller_order_id where"
                        + " so.order_id=? order by sh.created_at,sh.id",
                id);
    }

    @PostMapping("/admin/shipments/{id}/deliver")
    @Transactional
    Object deliver(@PathVariable UUID id) {
        Rules.require(
                shippingProvider.equals("local"), "Simulated delivery disabled with external carrier");
        delivery.delivered(id, Instant.now());
        s.audit("SIMULATE_DELIVERY", id);
        return Map.of("delivered", true);
    }

    @PostMapping("/orders/{id}/returns")
    @Transactional
    Object requestReturn(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Return request) {
        s.ownedOrder(id);
        s.db.queryForMap("select * from orders where id=? for update", id);
        String scope = key(key, request.itemId(), request.quantity());
        var prior =
                s.db.queryForList(
                        "select * from returns where order_id=? and idempotency_key=?", id, scope);
        if (!prior.isEmpty()) {
            var p = prior.getFirst();
            Rules.require(
                    p.get("order_item_id").equals(request.itemId())
                            && p.get("reason").equals(request.reason())
                            && (request.quantity() == null
                            || ((Number) p.get("quantity")).intValue() == request.quantity())
                            && (request.shipmentId() == null
                            || request.shipmentId().equals(p.get("shipment_id"))),
                    "Return key reused with different payload");
            return p;
        }
        var item =
                s.db.queryForMap(
                        "select * from order_items where id=? and order_id=?", request.itemId(), id);
        var eligible =
                s.db.queryForList(
                        "select sh.*,sh.quantity-coalesce((select sum(r.quantity) from returns r where"
                                + " r.shipment_id=sh.id),0) remaining from shipments sh where sh.order_item_id=?"
                                + " and sh.status='DELIVERED' and sh.delivered_at>now()-interval '30 days' and"
                                + " (?::uuid is null or sh.id=?::uuid) order by sh.created_at,sh.id",
                        request.itemId(),
                        request.shipmentId(),
                        request.shipmentId());
        var shipment =
                eligible.stream()
                        .filter(
                                sh ->
                                        ((Number) sh.get("remaining")).intValue() > 0
                                                && (request.quantity() == null
                                                || ((Number) sh.get("remaining")).intValue() >= request.quantity()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new org.springframework.web.server.ResponseStatusException(
                                                org.springframework.http.HttpStatus.CONFLICT,
                                                "No eligible delivered quantity within 30 days"));
        int q =
                request.quantity() == null
                        ? ((Number) shipment.get("remaining")).intValue()
                        : request.quantity();
        int before = ((Number) item.get("returned_quantity")).intValue();
        int total = ((Number) item.get("quantity")).intValue();
        Rules.require(
                s.db.update(
                        "update order_items set returned_quantity=returned_quantity+? where id=? and"
                                + " returned_quantity+?<=delivered_quantity",
                        q,
                        request.itemId(),
                        q)
                        == 1,
                "Return exceeds delivered quantity");
        long net =
                unitAllocation(((Number) item.get("net")).longValue(), before + q, total)
                        - unitAllocation(((Number) item.get("net")).longValue(), before, total);
        long tax =
                unitAllocation(((Number) item.get("tax")).longValue(), before + q, total)
                        - unitAllocation(((Number) item.get("tax")).longValue(), before, total);
        long amount = Math.addExact(net, tax);
        UUID returnId = UUID.randomUUID();
        s.db.update(
                "insert into"
                        + " returns(id,order_id,order_item_id,reason,status,shipment_id,quantity,idempotency_key,refund_amount,refund_net,refund_tax)"
                        + " values (?,?,?,?,'REQUESTED',?,?,?,?,?,?)",
                returnId,
                id,
                request.itemId(),
                request.reason(),
                shipment.get("id"),
                q,
                scope,
                amount,
                net,
                tax);
        return s.db.queryForMap("select * from returns where id=?", returnId);
    }

    @GetMapping("/returns/{id}")
    Object returned(@PathVariable UUID id) {
        return s.db.queryForMap(
                "select r.* from returns r join orders o on o.id=r.order_id where r.id=? and"
                        + " o.customer_id=?",
                id,
                s.user());
    }

    @PostMapping("/admin/returns/{id}/inspect")
    @Transactional
    Object inspect(@PathVariable UUID id, @RequestBody Inspection request) {
        var r = s.db.queryForMap("select * from returns where id=?", id);
        s.db.queryForMap("select * from orders where id=? for update", r.get("order_id"));
        r = s.db.queryForMap("select * from returns where id=? for update", id);
        if (!"REQUESTED".equals(r.get("status"))) return r;
        if (!request.accepted()) {
            s.db.update("update returns set status='REJECTED' where id=?", id);
            return Map.of("status", "REJECTED");
        }
        var item = s.db.queryForMap("select * from order_items where id=?", r.get("order_item_id"));
        if (request.restock()) {
            s.db.update(
                    "update inventory set on_hand=on_hand+?,version=version+1 where offer_id=?",
                    r.get("quantity"),
                    item.get("offer_id"));
            s.db.update(
                    "insert into inventory_movements(id,offer_id,delta,reason) values"
                            + " (?,?,?,'INSPECTED_RETURN')",
                    UUID.randomUUID(),
                    item.get("offer_id"),
                    r.get("quantity"));
        }
        UUID payment =
                s.db.queryForObject(
                        "select id from payments where order_id=?", UUID.class, r.get("order_id"));
        workflow.queueRefund(
                payment, id, "return:" + id, ((Number) r.get("refund_amount")).longValue());
        s.db.update("update returns set status='RECEIVED' where id=?", id);
        s.audit("RETURN_INSPECTED", id);
        return Map.of("status", "RECEIVED");
    }

    @GetMapping("/orders/{id}/refunds")
    Object refunds(@PathVariable UUID id) {
        s.ownedOrder(id);
        return s.db.queryForList(
                "select r.* from refunds r join payments p on p.id=r.payment_id where p.order_id=?", id);
    }

    @GetMapping("/admin/returns")
    Object adminReturns() {
        return s.db.queryForList("select * from returns order by created_at desc limit 100");
    }

    @GetMapping("/admin/shipments")
    Object adminShipments() {
        return s.db.queryForList("select * from shipments order by created_at desc limit 100");
    }

    @PostMapping("/admin/workflows/{id}/retry")
    @Transactional
    Object retry(@PathVariable UUID id) {
        s.db.update(
                "update checkout_workflows set status='READY',attempts=0,next_attempt=now() where"
                        + " order_id=? and status='MANUAL_REVIEW'",
                id);
        s.audit("WORKFLOW_RETRY", id);
        return Map.of("queued", true);
    }

    public record Ship(
            @NotNull UUID itemId,
            @NotBlank @Size(max = 100) String carrier,
            @NotBlank @Size(max = 150) String tracking,
            @Min(1) @Max(100) Integer quantity) {
    }

    public record Return(
            @NotNull UUID itemId,
            @NotBlank @Size(max = 500) String reason,
            @Min(1) @Max(100) Integer quantity,
            UUID shipmentId) {
    }

    public record Inspection(boolean accepted, boolean restock) {
    }
}

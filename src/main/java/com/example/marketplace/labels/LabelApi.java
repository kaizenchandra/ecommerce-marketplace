package com.example.marketplace.labels;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class LabelApi {
    private final Store s;
    private final LabelService labels;

    public LabelApi(Store s, LabelService labels) {
        this.s = s;
        this.labels = labels;
    }

    @PostMapping("/seller-orders/{id}/labels")
    Object purchase(
            @PathVariable UUID id,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Purchase request) {
        return labels.create(id, request.itemId(), request.quantity(), request.maxCostMinor(), key);
    }

    @GetMapping("/shipping-labels/{id}")
    Object label(@PathVariable UUID id) {
        return labels.owned(id);
    }

    @GetMapping("/seller/labels")
    Object list() {
        s.role("SELLER");
        return s.db.queryForList(
                "select l.* from shipping_labels l join seller_orders so on so.id=l.seller_order_id join"
                        + " sellers se on se.id=so.seller_id where se.owner_id=? order by l.created_at desc"
                        + " limit 100",
                s.user());
    }

    @PostMapping("/shipping-labels/{id}/dispatch")
    Object dispatch(@PathVariable UUID id) {
        return labels.dispatch(id);
    }

    @PostMapping("/shipping-labels/{id}/abort")
    Object abort(@PathVariable UUID id) {
        labels.abort(id);
        return Map.of("aborted", true);
    }

    @PostMapping("/shipping-labels/{id}/refresh")
    Object refresh(@PathVariable UUID id) {
        return labels.refresh(id);
    }

    @GetMapping(value = "/shipping-labels/{id}/document", produces = MediaType.TEXT_PLAIN_VALUE)
    String simulatedDocument(@PathVariable UUID id) {
        var row = labels.owned(id);
        Rules.require(
                row.get("provider_id") != null && row.get("provider_id").toString().startsWith("local_"),
                "Not a simulator label");
        return "SIMULATOR LABEL — NOT VALID POSTAGE\nLabel: "
                + id
                + "\nQuantity: "
                + row.get("quantity")
                + "\nTracking: "
                + row.get("tracking");
    }

    @GetMapping("/admin/shipping-labels")
    Object admin() {
        return s.db.queryForList("select * from shipping_labels order by created_at desc limit 100");
    }

    @PostMapping("/admin/shipping-labels/{id}/retry")
    Object retry(@PathVariable UUID id) {
        s.db.update(
                "update shipping_labels set status=case when purchase_started_at is null then 'READY' else"
                        + " 'ATTEMPTED' end,attempts=0,next_attempt=now() where id=? and"
                        + " status='MANUAL_REVIEW'",
                id);
        s.audit("LABEL_RETRY", id);
        return Map.of("queued", true);
    }

    @PostMapping("/admin/shipping-labels/{id}/reconcile/{providerId}")
    Object reconcile(@PathVariable UUID id, @PathVariable String providerId) {
        var row = s.db.queryForMap("select * from shipping_labels where id=?", id);
        Rules.require(row.get("purchase_started_at") != null, "No purchase attempt exists");
        s.audit("LABEL_PROVIDER_RECONCILE", id);
        return reconcileProvider(id, providerId);
    }

    private Object reconcileProvider(UUID id, String providerId) {
        return labels.reconcileKnown(id, providerId);
    }

    @PostMapping("/admin/shipping-labels/{id}/abort")
    Object adminAbort(@PathVariable UUID id) {
        labels.abortAdmin(id);
        s.audit("LABEL_PREPURCHASE_ABORT", id);
        return Map.of("aborted", true);
    }

    @PostMapping("/admin/shipping-labels/{id}/release-closed")
    Object release(@PathVariable UUID id) {
        labels.releaseClosed(id);
        return Map.of("resolved", true);
    }

    public record Purchase(
            @NotNull UUID itemId,
            @Min(1) @Max(100) int quantity,
            @Min(0) @Max(1000000000) long maxCostMinor) {
    }
}

package com.example.marketplace.delivery;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ShippingApi {
    private final Store s;

    public ShippingApi(Store s) {
        this.s = s;
    }

    @PutMapping("/sellers/{id}/shipping-profile")
    @Transactional
    Object profile(@PathVariable UUID id, @Valid @RequestBody Profile p) {
        s.seller(id);
        Rules.require(
                p.address().keySet().containsAll(Set.of("name", "street1", "city", "zip", "country"))
                        && s.json(p.address()).length() < 4000,
                "Complete origin address required");
        s.db.update(
                "insert into"
                        + " seller_shipping_profiles(seller_id,address,length_cm,width_cm,height_cm,max_weight_grams)"
                        + " values (?,?::jsonb,?,?,?,?) on conflict(seller_id) do update set"
                        + " address=excluded.address,length_cm=excluded.length_cm,width_cm=excluded.width_cm,height_cm=excluded.height_cm,max_weight_grams=excluded.max_weight_grams,version=seller_shipping_profiles.version+1",
                id,
                s.json(p.address()),
                p.lengthCm(),
                p.widthCm(),
                p.heightCm(),
                p.maxWeightGrams());
        return Map.of("saved", true);
    }

    @PostMapping("/admin/shipments/{id}/retry-tracking")
    Object retry(@PathVariable UUID id) {
        s.db.update(
                "update shipments set"
                        + " tracking_attempts=0,tracking_state='READY',tracking_next_attempt=now() where id=?"
                        + " and status='SHIPPED'",
                id);
        s.audit("CARRIER_RETRY", id);
        return Map.of("queued", true);
    }

    public record Profile(
            @NotNull Map<String, String> address,
            @Min(1) @Max(500) int lengthCm,
            @Min(1) @Max(500) int widthCm,
            @Min(1) @Max(500) int heightCm,
            @Min(1) @Max(1000000) int maxWeightGrams) {
    }
}

package com.example.marketplace.payment;

import com.example.marketplace.shared.Store;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/payment-events")
public class PaymentRecoveryApi {
    private final Store s;

    public PaymentRecoveryApi(Store s) {
        this.s = s;
    }

    @GetMapping
    Object events() {
        return s.db.queryForList(
                "select * from provider_webhook_inbox order by created_at desc limit 100");
    }

    @PostMapping("/{provider}/{eventId}/retry")
    @Transactional
    Object retry(@PathVariable String provider, @PathVariable String eventId) {
        s.db.update(
                "update provider_webhook_inbox set status='READY',attempts=0,next_attempt=now() where"
                        + " provider=? and event_id=? and status='MANUAL_REVIEW'",
                provider,
                eventId);
        s.audit("PAYMENT_EVENT_RETRY", provider + ":" + eventId);
        return Map.of("queued", true);
    }
}

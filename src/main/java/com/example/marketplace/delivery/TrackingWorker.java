package com.example.marketplace.delivery;

import com.example.marketplace.fulfillment.DeliveryService;
import com.example.marketplace.shared.Store;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperty(name = "marketplace.shipping.provider", havingValue = "shippo")
public class TrackingWorker {
    private final Store s;
    private final ShippingProvider provider;
    private final DeliveryService delivery;

    public TrackingWorker(Store s, ShippingProvider provider, DeliveryService delivery) {
        this.s = s;
        this.provider = provider;
        this.delivery = delivery;
    }

    @Scheduled(fixedDelay = 30000, initialDelayString = "${marketplace.worker-initial-delay:1000}")
    public void poll() {
        for (var row :
                s.db.queryForList(
                        "update shipments set"
                                + " tracking_attempts=tracking_attempts+1,tracking_next_attempt=now()+interval '60"
                                + " seconds' where id in (select id from shipments where status='SHIPPED' and"
                                + " tracking_state='READY' and tracking_attempts<8 and tracking_next_attempt<=now()"
                                + " order by tracking_next_attempt limit 16 for update skip locked) returning *")) {
            try {
                var state = provider.track(row.get("carrier").toString(), row.get("tracking").toString());
                if ("DELIVERED".equals(state.status()))
                    delivery.delivered((UUID) row.get("id"), state.occurredAt());
                else s.db.update("update shipments set tracking_attempts=0 where id=?", row.get("id"));
            } catch (Exception e) {
                /* lease and bounded attempt count retain retryable work */
            }
        }
        s.db.update(
                "update shipments set tracking_state='MANUAL_REVIEW' where status='SHIPPED' and"
                        + " tracking_state='READY' and tracking_attempts>=8 and tracking_next_attempt<=now()");
    }
}

package com.example.marketplace.configuration;

import com.example.marketplace.shared.Store;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarketplaceMetrics {
    @Bean
    MeterBinder backlog(Store s) {
        return registry -> {
            Gauge.builder(
                            "marketplace.labels.manual",
                            s,
                            x -> count(x, "select count(*) from shipping_labels where status='MANUAL_REVIEW'"))
                    .register(registry);
            Gauge.builder(
                            "marketplace.tax.reporting.manual",
                            s,
                            x -> count(x, "select count(*) from tax_reporting_jobs where status='MANUAL_REVIEW'"))
                    .register(registry);
            Gauge.builder(
                            "marketplace.tax.reporting.pending",
                            s,
                            x -> count(x, "select count(*) from tax_reporting_jobs where status='READY'"))
                    .register(registry);
            Gauge.builder(
                            "marketplace.outbox.manual",
                            s,
                            x ->
                                    count(
                                            x, "select count(*) from outbox_events where delivery_state='MANUAL_REVIEW'"))
                    .register(registry);
            Gauge.builder(
                            "marketplace.shipping.manual",
                            s,
                            x -> count(x, "select count(*) from shipments where tracking_state='MANUAL_REVIEW'"))
                    .register(registry);
            Gauge.builder(
                            "marketplace.outbox.pending",
                            s,
                            x -> count(x, "select count(*) from outbox_events where published_at is null"))
                    .register(registry);
            Gauge.builder(
                            "marketplace.saga.pending",
                            s,
                            x -> count(x, "select count(*) from checkout_workflows where status='READY'"))
                    .register(registry);
            Gauge.builder(
                            "marketplace.saga.manual",
                            s,
                            x -> count(x, "select count(*) from checkout_workflows where status='MANUAL_REVIEW'"))
                    .register(registry);
            Gauge.builder(
                            "marketplace.refund.pending",
                            s,
                            x -> count(x, "select count(*) from refunds where status<>'COMPLETED'"))
                    .register(registry);
            Gauge.builder(
                            "marketplace.inventory.invalid",
                            s,
                            x -> count(x, "select count(*) from inventory where reserved<0 or on_hand<reserved"))
                    .register(registry);
        };
    }

    private double count(Store s, String sql) {
        try {
            return s.db.queryForObject(sql, Long.class);
        } catch (Exception e) {
            return Double.NaN;
        }
    }
}

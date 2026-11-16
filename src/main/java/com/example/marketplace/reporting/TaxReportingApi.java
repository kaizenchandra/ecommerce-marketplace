package com.example.marketplace.reporting;

import com.example.marketplace.shared.Store;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/tax-reporting")
public class TaxReportingApi {
    private final Store s;
    private final TaxReporting reports;

    public TaxReportingApi(Store s, TaxReporting reports) {
        this.s = s;
        this.reports = reports;
    }

    @GetMapping
    Object list() {
        return s.db.queryForList("select * from tax_reporting_jobs order by created_at desc limit 100");
    }

    @PostMapping("/{id}/retry")
    Object retry(@PathVariable UUID id) {
        s.db.update(
                "update tax_reporting_jobs set status='READY',attempts=0,next_attempt=now() where id=? and"
                        + " status='MANUAL_REVIEW'",
                id);
        s.audit("TAX_REPORT_RETRY", id);
        return Map.of("queued", true);
    }

    @PostMapping("/{id}/reconcile/{providerId}")
    Object reconcile(@PathVariable UUID id, @PathVariable String providerId) {
        reports.reconcileKnown(id, providerId);
        s.audit("TAX_REPORT_PROVIDER_RECONCILE", id);
        return Map.of("queued", true);
    }
}

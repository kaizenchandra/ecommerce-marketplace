package com.example.marketplace.labels;

import com.example.marketplace.shared.Store;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        name = "marketplace.shipping.provider",
        havingValue = "local",
        matchIfMissing = true)
public class LocalLabels implements LabelGateway {
    private final Store s;

    public LocalLabels(Store s) {
        this.s = s;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Result purchase(UUID operation, String rate) {
        s.db.update(
                "insert into local_label_ledger values (?,?,?) on conflict do nothing",
                operation,
                "local_" + operation,
                rate);
        return reconcile(operation, rate, Instant.now()).orElseThrow();
    }

    public Result retrieve(String id) {
        var row = s.db.queryForMap("select * from local_label_ledger where provider_id=?", id);
        return record(row);
    }

    public Optional<Result> reconcile(UUID operation, String rate, Instant since) {
        var rows =
                s.db.queryForList("select * from local_label_ledger where operation_id=?", operation);
        return rows.isEmpty() ? Optional.empty() : Optional.of(record(rows.getFirst()));
    }

    private Result record(Map<String, Object> row) {
        return new Result(
                row.get("provider_id").toString(),
                "SUCCESS",
                row.get("rate_id").toString(),
                "marketplace-label:" + row.get("operation_id"),
                "local",
                "SIM-" + row.get("operation_id").toString().substring(0, 8),
                "/api/v1/shipping-labels/" + row.get("operation_id") + "/document");
    }
}

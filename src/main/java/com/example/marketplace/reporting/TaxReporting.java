package com.example.marketplace.reporting;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class TaxReporting {
    private final Store s;
    private final TransactionTemplate tx;
    private final ObjectProvider<TaxReporter> providers;

    public TaxReporting(
            Store s, PlatformTransactionManager manager, ObjectProvider<TaxReporter> providers) {
        this.s = s;
        tx = new TransactionTemplate(manager);
        this.providers = providers;
    }

    private JsonNode json(Object raw) {
        try {
            return s.json.readTree(raw.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid tax snapshot");
        }
    }

    public void enqueueSale(UUID order) {
        var quote =
                s.db.queryForMap(
                        "select q.* from orders o join checkout_quotes q on q.id=o.quote_id where o.id=?",
                        order);
        JsonNode details = json(quote.get("provider_details"));
        for (var group :
                s.db.queryForList("select * from seller_orders where order_id=? order by id", order)) {
            var info = details.path(group.get("seller_id").toString());
            String calculation = info.path("taxRef").asText();
            if (!calculation.startsWith("taxcalc_")) continue;
            List<Map<String, Object>> lines =
                    s.db.queryForList(
                            "select offer_id as \"offerId\",net,tax from order_items where seller_order_id=?"
                                    + " order by offer_id",
                            group.get("id"));
            Map<String, Object> request =
                    Map.of(
                            "calculation",
                            calculation,
                            "lines",
                            lines,
                            "shipping",
                            info.path("shippingMinor").asLong(),
                            "shippingTax",
                            info.path("shippingTaxMinor").asLong(),
                            "postedAt",
                            Instant.now().getEpochSecond());
            s.db.update(
                    "insert into tax_reporting_jobs(id,order_id,seller_order_id,operation_key,kind,request)"
                            + " values (?,?,?,?,'SALE',?::jsonb) on conflict(operation_key) do nothing",
                    UUID.randomUUID(),
                    order,
                    group.get("id"),
                    "tax-sale:" + order + ":" + group.get("id"),
                    s.json(request));
        }
        s.db.update("update payments set tax_reporting_checked=true where order_id=?", order);
    }

    public void enqueueReversals(UUID refund) {
        var row =
                s.db.queryForMap(
                        "select r.*,p.order_id from refunds r join payments p on p.id=r.payment_id where"
                                + " r.id=?",
                        refund);
        UUID order = (UUID) row.get("order_id");
        enqueueSale(order);
        for (var sale :
                s.db.queryForList(
                        "select * from tax_reporting_jobs where order_id=? and kind='SALE'", order)) {
            Map<String, Object> request;
            if (row.get("return_id") == null) {
                var original = json(sale.get("request"));
                request =
                        Map.of(
                                "full",
                                true,
                                "lines",
                                original.path("lines"),
                                "shipping",
                                original.path("shipping").asLong(),
                                "shippingTax",
                                original.path("shippingTax").asLong());
            } else {
                var item =
                        s.db.queryForMap(
                                "select r.refund_net,r.refund_tax,i.offer_id,i.seller_order_id from returns r join"
                                        + " order_items i on i.id=r.order_item_id where r.id=?",
                                row.get("return_id"));
                if (!item.get("seller_order_id").equals(sale.get("seller_order_id"))) continue;
                // Historical partial taxed refunds without reliable components must be reconciled manually.
                request =
                        Map.of(
                                "full",
                                false,
                                "legacyComponentsMissing",
                                item.get("refund_net") == null || item.get("refund_tax") == null,
                                "lines",
                                List.of(
                                        Map.of(
                                                "offerId",
                                                item.get("offer_id"),
                                                "net",
                                                item.get("refund_net") == null ? 0 : item.get("refund_net"),
                                                "tax",
                                                item.get("refund_tax") == null ? 0 : item.get("refund_tax"))),
                                "shipping",
                                0,
                                "shippingTax",
                                0);
            }
            s.db.update(
                    "insert into"
                            + " tax_reporting_jobs(id,order_id,seller_order_id,refund_id,operation_key,kind,dependency_id,request)"
                            + " values (?,?,?,?,?,'REVERSAL',?,?::jsonb) on conflict(operation_key) do nothing",
                    UUID.randomUUID(),
                    order,
                    sale.get("seller_order_id"),
                    refund,
                    "tax-reversal:" + refund + ":" + sale.get("seller_order_id"),
                    sale.get("id"),
                    s.json(request));
        }
        s.db.update("update refunds set tax_reporting_checked=true where id=?", refund);
    }

    @Scheduled(
            fixedDelay = 1000,
            initialDelayString = "${marketplace.worker-initial-delay:1000}",
            scheduler = "reportingScheduler")
    public void poll() {
        if (providers.getIfAvailable() == null) return;
        for (var row :
                s.db.queryForList(
                        "select id from tax_reporting_jobs where status='READY' and next_attempt<=now() order"
                                + " by created_at limit 8"))
            try {
                process((UUID) row.get("id"));
            } catch (Exception e) {
                s.db.update(
                        "update tax_reporting_jobs set last_error=? where id=?",
                        e.getClass().getSimpleName(),
                        row.get("id"));
            }
    }

    @Scheduled(
            fixedDelay = 60000,
            initialDelayString = "${marketplace.worker-initial-delay:1000}",
            scheduler = "reportingScheduler")
    public void backfill() {
        if (providers.getIfAvailable() == null) return;
        for (var row :
                s.db.queryForList(
                        "select order_id from payments where not tax_reporting_checked and status in"
                                + " ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED') order by order_id limit 32"))
            tx.executeWithoutResult(
                    t -> {
                        s.db.queryForMap("select * from orders where id=? for update", row.get("order_id"));
                        enqueueSale((UUID) row.get("order_id"));
                    });
        for (var row :
                s.db.queryForList(
                        "select r.id,p.order_id from refunds r join payments p on p.id=r.payment_id where not"
                                + " r.tax_reporting_checked and r.status='COMPLETED' order by r.id limit 32"))
            tx.executeWithoutResult(
                    t -> {
                        s.db.queryForMap("select * from orders where id=? for update", row.get("order_id"));
                        enqueueReversals((UUID) row.get("id"));
                    });
    }

    public void process(UUID id) {
        var provider = providers.getIfAvailable();
        if (provider == null) return;
        var job =
                tx.execute(
                        t -> {
                            var row =
                                    s.db.queryForMap("select *,next_attempt<=clock_timestamp() as due from tax_reporting_jobs where id=? for update", id);
                            if (!"READY".equals(row.get("status"))
                                    || !Boolean.TRUE.equals(row.get("due"))) return null;
                            if (row.get("dependency_id") != null
                                    && !"DONE"
                                    .equals(
                                            s.db.queryForObject(
                                                    "select status from tax_reporting_jobs where id=?",
                                                    String.class,
                                                    row.get("dependency_id")))) {
                                s.db.update(
                                        "update tax_reporting_jobs set next_attempt=now()+interval '30 seconds' where"
                                                + " id=?",
                                        id);
                                return null;
                            }
                            if (((Number) row.get("attempts")).intValue() >= 8
                                    || json(row.get("request")).path("legacyComponentsMissing").asBoolean()
                                    || (row.get("provider_id") == null
                                    && row.get("provider_started_at") != null
                                    && ((java.sql.Timestamp) row.get("provider_started_at"))
                                    .toInstant()
                                    .isBefore(Instant.now().minus(Duration.ofHours(23))))) {
                                s.db.update("update tax_reporting_jobs set status='MANUAL_REVIEW' where id=?", id);
                                return null;
                            }
                            s.db.update(
                                    "update tax_reporting_jobs set attempts=attempts+1,next_attempt=now()+interval"
                                            + " '30 seconds',provider_started_at=coalesce(provider_started_at,now())"
                                            + " where id=?",
                                    id);
                            return row;
                        });
        if (job == null) return;
        JsonNode request = json(job.get("request"));
        JsonNode original =
                job.get("dependency_id") == null
                        ? null
                        : json(
                        s.db.queryForObject(
                                "select response::text from tax_reporting_jobs where id=?",
                                String.class,
                                job.get("dependency_id")));
        String key = job.get("operation_key").toString();
        JsonNode response =
                job.get("provider_id") != null
                        ? provider.retrieve(job.get("provider_id").toString())
                        : "SALE".equals(job.get("kind"))
                        ? provider.sale(key, request)
                        : provider.reversal(key, request, original);
        String reference = response.path("id").asText();
        // Save reference before validation/finalization, so a local failure cannot cause a new POST.
        s.db.update(
                "update tax_reporting_jobs set provider_id=? where id=? and provider_id is null",
                reference,
                id);
        validate(key, job.get("kind").toString(), request, response, original);
        tx.executeWithoutResult(
                t -> {
                    s.db.queryForMap("select * from orders where id=? for update", job.get("order_id"));
                    if (s.db.update(
                            "update tax_reporting_jobs set status='DONE',response=?::jsonb,last_error=null"
                                    + " where id=? and status='READY'",
                            s.json(response),
                            id)
                            == 1)
                        s.event(
                                (UUID) job.get("order_id"),
                                "SALE".equals(job.get("kind")) ? "TaxSaleReported" : "TaxReversalReported");
                });
    }

    public void reconcileKnown(UUID id, String providerId) {
        Rules.require(providerId.matches("tax_[A-Za-z0-9_]+"), "Invalid tax provider ID");
        var row = s.db.queryForMap("select * from tax_reporting_jobs where id=?", id);
        Rules.require(row.get("provider_started_at") != null, "No tax operation attempt recorded");
        JsonNode response = providers.getObject().retrieve(providerId);
        JsonNode original =
                row.get("dependency_id") == null
                        ? null
                        : json(
                        s.db.queryForObject(
                                "select response::text from tax_reporting_jobs where id=?",
                                String.class,
                                row.get("dependency_id")));
        validate(
                row.get("operation_key").toString(),
                row.get("kind").toString(),
                json(row.get("request")),
                response,
                original);
        s.db.update(
                "update tax_reporting_jobs set provider_id=?,status='READY',next_attempt=now(),attempts=0"
                        + " where id=? and status<>'DONE'",
                providerId,
                id);
    }

    private void validate(
            String key, String kind, JsonNode request, JsonNode response, JsonNode original) {
        boolean reverse = kind.equals("REVERSAL");
        Rules.require(
                response.has("livemode")
                        && !response.path("livemode").asBoolean()
                        && "inr".equals(response.path("currency").asText())
                        && key.equals(response.path("reference").asText())
                        && (reverse ? "reversal" : "transaction").equals(response.path("type").asText())
                        && !response.path("line_items").path("has_more").asBoolean(),
                "Tax reporting identity mismatch");
        if (reverse)
            Rules.require(
                    original
                            .path("id")
                            .asText()
                            .equals(response.path("reversal").path("original_transaction").asText()),
                    "Wrong original tax transaction");
        Map<String, JsonNode> expected = new HashMap<>();
        request.path("lines").forEach(line -> expected.put(line.path("offerId").asText(), line));
        Set<String> seen = new HashSet<>();
        for (JsonNode line : response.path("line_items").path("data")) {
            String ref = line.path("reference").asText();
            JsonNode item = expected.get(ref);
            if (reverse) {
                item = null;
                String originalId = line.path("reversal").path("original_line_item").asText();
                for (JsonNode source : original.path("line_items").path("data"))
                    if (source.path("id").asText().equals(originalId)) {
                        ref = source.path("reference").asText();
                        item = expected.get(ref);
                        break;
                    }
            }
            Rules.require(
                    item != null
                            && seen.add(ref)
                            && line.path("amount").asLong(Long.MAX_VALUE)
                            == (reverse ? -1 : 1) * item.path("net").asLong()
                            && line.path("amount_tax").asLong(Long.MAX_VALUE)
                            == (reverse ? -1 : 1) * item.path("tax").asLong(),
                    "Tax report line amounts mismatch");
        }
        Rules.require(seen.equals(expected.keySet()), "Incomplete tax transaction lines");
        Rules.require(
                response.path("shipping_cost").path("amount").asLong(0)
                        == (reverse ? -1 : 1) * request.path("shipping").asLong()
                        && response.path("shipping_cost").path("amount_tax").asLong(0)
                        == (reverse ? -1 : 1) * request.path("shippingTax").asLong(),
                "Tax report shipping mismatch");
    }
}

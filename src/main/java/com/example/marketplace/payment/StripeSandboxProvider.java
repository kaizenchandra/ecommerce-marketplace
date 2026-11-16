package com.example.marketplace.payment;

import com.example.marketplace.shared.Rules;
import com.example.marketplace.shared.Store;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stripe test-mode hosted Checkout. No PAN, CVV, or browser-supplied payment method enters this
 * app.
 */
@Service
@ConditionalOnProperty(name = "marketplace.payment.provider", havingValue = "stripe")
public class StripeSandboxProvider implements PaymentProvider {
    private final Store s;
    private final RestClient http;
    private final TransactionTemplate tx;
    private final String origin;

    public StripeSandboxProvider(
            Store s,
            PlatformTransactionManager manager,
            @Value("${marketplace.stripe.secret-key}") String secret,
            @Value("${marketplace.stripe.api-base:https://api.stripe.com}") String base,
            @Value("${marketplace.public-url}") String origin,
            @Value("${marketplace.stripe.timeout-ms:5000}") long timeoutMs) {
        Rules.require(secret.startsWith("sk_test_"), "Only Stripe test-mode keys are accepted");
        URI uri = URI.create(base);
        Rules.require(
                base.equals("https://api.stripe.com")
                        || (uri.getScheme().equals("http")
                        && Set.of("localhost", "127.0.0.1").contains(uri.getHost())),
                "Invalid Stripe API endpoint");
        URI site = URI.create(origin);
        Rules.require(
                site.getScheme().equals("https")
                        || (site.getScheme().equals("http")
                        && Set.of("localhost", "127.0.0.1").contains(site.getHost())),
                "Public URL requires HTTPS");
        this.s = s;
        this.origin = origin.replaceAll("/$", "");
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(2))
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .build());
        Rules.require(timeoutMs >= 100 && timeoutMs <= 10000, "Invalid Stripe timeout");
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));
        http =
                RestClient.builder()
                        .baseUrl(base)
                        .requestFactory(factory)
                        .defaultHeader("Authorization", "Bearer " + secret)
                        .defaultHeader("Stripe-Version", "2026-10-28.endive")
                        .build();
    }

    private Map<String, Object> operation(String key, String kind, String payment, long amount) {
        return tx.execute(
                t -> {
                    s.db.update(
                            "insert into stripe_operations(operation_key,kind,payment_key,amount) values"
                                    + " (?,?,?,?) on conflict do nothing",
                            key,
                            kind,
                            payment,
                            amount);
                    var row = s.db.queryForMap("select * from stripe_operations where operation_key=?", key);
                    Rules.require(
                            kind.equals(row.get("kind"))
                                    && payment.equals(row.get("payment_key"))
                                    && amount == ((Number) row.get("amount")).longValue(),
                            "Provider operation key reused");
                    return row;
                });
    }

    private void withinRetryWindow(Map<String, Object> operation) {
        // Stripe may prune idempotency keys after 24h. Never blindly POST an ambiguous operation later.
        if (((java.sql.Timestamp) operation.get("created_at"))
                .toInstant()
                .isBefore(Instant.now().minus(Duration.ofHours(23))))
            throw new IllegalStateException(
                    "Ambiguous Stripe operation past retry window; manual reconciliation required");
    }

    private JsonNode get(String path) {
        return checked(http.get().uri(path).retrieve().body(JsonNode.class));
    }

    private JsonNode post(String path, String key, Map<String, String> values) {
        var form = new LinkedMultiValueMap<String, String>();
        values.forEach(form::add);
        return checked(
                http.post()
                        .uri(path)
                        .header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class));
    }

    private JsonNode checked(JsonNode node) {
        if (node == null || !node.has("livemode") || node.path("livemode").asBoolean())
            throw new IllegalStateException("Unexpected Stripe/live-mode response");
        return node;
    }

    private String reference(JsonNode node, String prefix) {
        String id = node.path("id").asText();
        if (!id.matches(prefix + "[A-Za-z0-9_]+"))
            throw new IllegalStateException("Unexpected Stripe object ID");
        return id;
    }

    public Result capture(String key, long amount) {
        var op = operation("checkout:" + key, "CHECKOUT", key, amount);
        JsonNode node;
        if (op.get("provider_id") != null) node = get("/v1/checkout/sessions/" + op.get("provider_id"));
        else {
            withinRetryWindow(op);
            node =
                    post(
                            "/v1/checkout/sessions",
                            "checkout:" + key,
                            Map.ofEntries(
                                    Map.entry("mode", "payment"),
                                    Map.entry("client_reference_id", key),
                                    Map.entry("metadata[order_id]", key),
                                    Map.entry("payment_intent_data[metadata][order_id]", key),
                                    Map.entry("payment_method_types[0]", "card"),
                                    Map.entry("line_items[0][quantity]", "1"),
                                    Map.entry("line_items[0][price_data][currency]", "inr"),
                                    Map.entry("line_items[0][price_data][unit_amount]", Long.toString(amount)),
                                    Map.entry(
                                            "line_items[0][price_data][product_data][name]", "Marketplace order " + key),
                                    Map.entry("success_url", origin + "/?payment=returned"),
                                    Map.entry("cancel_url", origin + "/?payment=cancelled")));
            String id = reference(node, "cs_");
            tx.executeWithoutResult(
                    t ->
                            s.db.update(
                                    "update stripe_operations set provider_id=? where operation_key=?",
                                    id,
                                    "checkout:" + key));
        }
        return result(key, amount, node);
    }

    private Result result(String key, long amount, JsonNode node) {
        String id = reference(node, "cs_");
        if (!key.equals(node.path("client_reference_id").asText())
                || !"inr".equals(node.path("currency").asText())
                || node.path("amount_total").asLong(-1) != amount)
            throw new IllegalStateException("Stripe checkout identity/amount mismatch");
        String status =
                node.path("payment_status").asText().equals("paid")
                        || node.path("payment_status").asText().equals("no_payment_required")
                        ? "CAPTURED"
                        : node.path("status").asText().equals("expired") ? "FAILED" : "PENDING";
        String url = null;
        if ("PENDING".equals(status) && node.hasNonNull("url")) {
            URI u = URI.create(node.get("url").asText());
            if (!"https".equals(u.getScheme()) || !"checkout.stripe.com".equals(u.getHost()))
                throw new IllegalStateException("Untrusted checkout redirect");
            url = u.toString();
        }
        return new Result(id, status, amount, 0, url);
    }

    public Optional<Result> find(String key) {
        var rows =
                s.db.queryForList(
                        "select * from stripe_operations where operation_key=?", "checkout:" + key);
        if (rows.isEmpty()) return Optional.empty();
        // Repeating the SAME request recovers a lost create response inside the provider retention
        // window.
        return Optional.of(capture(key, ((Number) rows.getFirst().get("amount")).longValue()));
    }

    public Result lookup(String key) {
        return find(key).orElseThrow(() -> new IllegalStateException("Unknown Stripe checkout"));
    }

    public Optional<Result> cancel(String key) {
        var found = find(key);
        if (found.isEmpty() || !"PENDING".equals(found.get().status())) return found;
        try {
            post(
                    "/v1/checkout/sessions/" + found.get().reference() + "/expire",
                    "expire:" + key,
                    Map.of());
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            if (e.getStatusCode().value() != 400) throw e;
        }
        // Paid concurrently? Compensation follows the authoritative reread.
        var outcome = find(key);
        if (outcome.isPresent() && "PENDING".equals(outcome.get().status()))
            throw new IllegalStateException("Stripe expiration unresolved; retry reconciliation");
        return outcome;
    }

    public String refund(String payment, String key, long amount) {
        if (amount == 0) return "zero:" + key; // Free order: no financial provider refund exists.
        var checkout = lookup(payment);
        Rules.require("CAPTURED".equals(checkout.status()), "Stripe capture required");
        JsonNode session = get("/v1/checkout/sessions/" + checkout.reference());
        String intent = session.path("payment_intent").asText();
        if (!intent.matches("pi_[A-Za-z0-9_]+"))
            throw new IllegalStateException("Missing payment intent");
        var op = operation("refund:" + key, "REFUND", payment, amount);
        JsonNode node;
        if (op.get("provider_id") != null) node = get("/v1/refunds/" + op.get("provider_id"));
        else {
            withinRetryWindow(op);
            node =
                    post(
                            "/v1/refunds",
                            "refund:" + key,
                            Map.of(
                                    "payment_intent",
                                    intent,
                                    "amount",
                                    Long.toString(amount),
                                    "metadata[operation_key]",
                                    key));
            String ref = reference(node, "re_");
            tx.executeWithoutResult(
                    t ->
                            s.db.update(
                                    "update stripe_operations set provider_id=? where operation_key=?",
                                    ref,
                                    "refund:" + key));
        }
        if (node.path("amount").asLong(-1) != amount
                || !intent.equals(node.path("payment_intent").asText()))
            throw new IllegalStateException("Stripe refund mismatch");
        if (!"succeeded".equals(node.path("status").asText()))
            throw new IllegalStateException("Stripe refund unresolved; retain budget");
        return reference(node, "re_");
    }
}

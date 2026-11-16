package com.example.marketplace.reporting;

import com.example.marketplace.shared.ProviderHttp;
import com.example.marketplace.shared.Rules;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Service
@ConditionalOnProperty(name = "marketplace.tax.provider", havingValue = "stripe")
public class StripeTaxReporter implements TaxReporter {
    private final RestClient http;

    public StripeTaxReporter(
            @Value("${marketplace.tax.stripe-secret-key}") String secret,
            @Value("${marketplace.tax.api-base:https://api.stripe.com}") String base,
            @Value("${marketplace.providers.timeout-ms:5000}") long timeout) {
        Rules.require(secret.startsWith("sk_test_"), "Test-mode Stripe Tax credentials required");
        http = ProviderHttp.client(base, "https://api.stripe.com", "Bearer " + secret, timeout);
    }

    private JsonNode post(String path, String key, LinkedMultiValueMap<String, String> form) {
        return checked(
                http.post()
                        .uri(path)
                        .header("Stripe-Version", "2026-10-28.endive")
                        .header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class));
    }

    private JsonNode checked(JsonNode node) {
        Rules.require(
                node != null
                        && node.has("livemode")
                        && !node.path("livemode").asBoolean()
                        && node.path("id").asText().matches("tax_[A-Za-z0-9_]+"),
                "Invalid test tax transaction");
        return node;
    }

    public JsonNode retrieve(String id) {
        Rules.require(id.matches("tax_[A-Za-z0-9_]+"), "Invalid tax transaction reference");
        return checked(
                http.get()
                        .uri("/v1/tax/transactions/{id}?expand[0]=line_items", id)
                        .header("Stripe-Version", "2026-10-28.endive")
                        .retrieve()
                        .body(JsonNode.class));
    }

    public JsonNode sale(String key, JsonNode request) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("calculation", request.path("calculation").asText());
        form.add("posted_at", request.path("postedAt").asText());
        form.add("reference", key);
        form.add("expand[0]", "line_items");
        form.add("metadata[operation_key]", key);
        return post("/v1/tax/transactions/create_from_calculation", key, form);
    }

    public JsonNode reversal(String key, JsonNode request, JsonNode original) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("reference", key);
        form.add("original_transaction", original.path("id").asText());
        form.add("mode", request.path("full").asBoolean() ? "full" : "partial");
        form.add("expand[0]", "line_items");
        form.add("metadata[operation_key]", key);
        if (!request.path("full").asBoolean()) {
            int n = 0;
            for (JsonNode line : request.path("lines")) {
                JsonNode source = null;
                for (JsonNode candidate : original.path("line_items").path("data"))
                    if (candidate.path("reference").asText().equals(line.path("offerId").asText()))
                        source = candidate;
                Rules.require(source != null, "Original tax line missing");
                String prefix = "line_items[" + n++ + "]";
                form.add(prefix + "[original_line_item]", source.path("id").asText());
                form.add(prefix + "[reference]", line.path("offerId").asText());
                form.add(prefix + "[amount]", Long.toString(-line.path("net").asLong()));
                form.add(prefix + "[amount_tax]", Long.toString(-line.path("tax").asLong()));
            }
        }
        return post("/v1/tax/transactions/create_reversal", key, form);
    }
}

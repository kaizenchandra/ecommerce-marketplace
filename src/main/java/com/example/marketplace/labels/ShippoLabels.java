package com.example.marketplace.labels;

import com.example.marketplace.shared.ProviderHttp;
import com.example.marketplace.shared.Rules;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.*;

@Service
@ConditionalOnProperty(name = "marketplace.shipping.provider", havingValue = "shippo")
public class ShippoLabels implements LabelGateway {
    private final RestClient http;

    public ShippoLabels(
            @Value("${marketplace.shippo.token}") String token,
            @Value("${marketplace.shippo.api-base:https://api.goshippo.com}") String base,
            @Value("${marketplace.providers.timeout-ms:5000}") long timeout) {
        Rules.require(token.startsWith("shippo_test_"), "Only test label purchases supported");
        http = ProviderHttp.client(base, "https://api.goshippo.com", "ShippoToken " + token, timeout);
    }

    private Result result(JsonNode node) {
        Rules.require(
                node != null && node.path("test").asBoolean() && !node.path("object_id").asText().isBlank(),
                "Invalid Shippo label transaction");
        String id = node.path("object_id").asText();
        Rules.require(id.matches("[A-Za-z0-9_-]+"), "Invalid label ID");
        String url = node.path("label_url").asText(null);
        if (url != null && !url.isBlank()) {
            URI uri = URI.create(url);
            Rules.require(
                    "https".equals(uri.getScheme())
                            && Set.of(
                                    "shippo-delivery.s3.amazonaws.com",
                                    "deliver.goshippo.com",
                                    "api.goshippo.com")
                            .contains(uri.getHost()),
                    "Untrusted label download URL");
        }
        JsonNode rate = node.path("rate");
        String rateId = rate.isObject() ? rate.path("object_id").asText() : rate.asText();
        String carrier = node.path("tracking_url_provider").asText();
        // Carrier token is resolved from the authoritative rate, not a human-readable display name.
        JsonNode detail =
                rate.isObject()
                        ? rate
                        : http.get()
                        .uri("/rates/{id}/", rateId)
                        .header("SHIPPO-API-VERSION", "2018-02-08")
                        .retrieve()
                        .body(JsonNode.class);
        if (detail != null && detail.path("carrier_account").isTextual()) {
            JsonNode account =
                    http.get()
                            .uri("/carrier_accounts/{id}/", detail.path("carrier_account").asText())
                            .retrieve()
                            .body(JsonNode.class);
            carrier = account == null ? "" : account.path("carrier").asText();
        } else carrier = "";
        return new Result(
                id,
                node.path("status").asText(),
                rateId,
                node.path("metadata").asText(),
                carrier,
                node.path("tracking_number").asText(),
                url);
    }

    public Result purchase(UUID operation, String rate) {
        return result(
                http.post()
                        .uri("/transactions/")
                        .header("SHIPPO-API-VERSION", "2018-02-08")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(
                                Map.of(
                                        "rate",
                                        rate,
                                        "metadata",
                                        "marketplace-label:" + operation,
                                        "label_file_type",
                                        "PDF_4x6",
                                        "async",
                                        false))
                        .retrieve()
                        .body(JsonNode.class));
    }

    public Result retrieve(String id) {
        Rules.require(id.matches("[A-Za-z0-9_-]+"), "Invalid label transaction ID");
        return result(
                http.get()
                        .uri("/transactions/{id}/", id)
                        .header("SHIPPO-API-VERSION", "2018-02-08")
                        .retrieve()
                        .body(JsonNode.class));
    }

    public Optional<Result> reconcile(UUID operation, String rate, Instant since) {
        // No documented purchase idempotency guarantee: search only, NEVER repeat the purchase POST.
        String expected = "marketplace-label:" + operation;
        List<JsonNode> matches = new ArrayList<>();
        for (int page = 1; page <= 5; page++) {
            JsonNode list =
                    http.get()
                            .uri(
                                    "/transactions/?page={page}&results=100&object_created_gte={since}",
                                    page,
                                    since.minusSeconds(300).toString())
                            .header("SHIPPO-API-VERSION", "2018-02-08")
                            .retrieve()
                            .body(JsonNode.class);
            Rules.require(
                    list != null && list.path("results").isArray(), "Invalid Shippo reconciliation page");
            for (JsonNode row : list.path("results"))
                if (expected.equals(row.path("metadata").asText())) matches.add(row);
            if (list.path("next").isNull() || list.path("next").asText().isBlank()) break;
        }
        Rules.require(
                matches.size() <= 1,
                "Multiple provider labels for one operation; manual reconciliation required");
        if (matches.isEmpty()) return Optional.empty();
        Result match = result(matches.getFirst());
        Rules.require(match.rateId().equals(rate), "Provider label rate mismatch");
        return Optional.of(match);
    }
}

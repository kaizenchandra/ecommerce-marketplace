package com.example.marketplace.delivery;

import com.example.marketplace.shared.ProviderHttp;
import com.example.marketplace.shared.Rules;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "marketplace.shipping.provider", havingValue = "shippo")
public class ShippoShipping implements ShippingProvider {
    private final RestClient http;

    public ShippoShipping(
            @Value("${marketplace.shippo.token}") String token,
            @Value("${marketplace.shippo.api-base:https://api.goshippo.com}") String base,
            @Value("${marketplace.providers.timeout-ms:5000}") long timeout) {
        Rules.require(token.startsWith("shippo_test_"), "Only Shippo test credentials accepted");
        http = ProviderHttp.client(base, "https://api.goshippo.com", "ShippoToken " + token, timeout);
    }

    public Rate quote(
            Map<String, Object> origin, Map<String, Object> destination, Map<String, Object> parcel) {
        Rules.require(
                !origin.isEmpty() && !parcel.isEmpty(),
                "Seller shipping profile and product weights required");
        JsonNode response =
                http.post()
                        .uri("/shipments/")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(
                                Map.of(
                                        "address_from",
                                        origin,
                                        "address_to",
                                        destination,
                                        "parcels",
                                        List.of(parcel),
                                        "async",
                                        false))
                        .retrieve()
                        .body(JsonNode.class);
        Rules.require(
                response != null && response.path("test").asBoolean() && response.path("rates").isArray(),
                "Invalid Shippo test rate response");
        JsonNode best = null;
        long amount = Long.MAX_VALUE;
        for (JsonNode rate : response.path("rates")) {
            if (!"INR".equals(rate.path("currency").asText())) continue;
            long price = Rules.minor(new java.math.BigDecimal(rate.path("amount").asText()));
            if (price < amount) {
                best = rate;
                amount = price;
            }
        }
        Rules.require(
                best != null && !best.path("object_id").asText().isBlank(),
                "No eligible INR shipping rate; no currency conversion fallback");
        return new Rate(
                amount,
                best.path("object_id").asText(),
                Map.of(
                        "provider",
                        "shippo",
                        "shipmentId",
                        response.path("object_id").asText(),
                        "rateId",
                        best.path("object_id").asText(),
                        "carrier",
                        best.path("provider").asText()));
    }

    public Tracking track(String carrier, String tracking) {
        Rules.require(
                carrier.matches("[a-z0-9_]{1,100}") && tracking.matches("[A-Za-z0-9_-]{1,150}"),
                "Invalid carrier tracking reference");
        JsonNode response =
                http.get()
                        .uri("/tracks/{carrier}/{tracking}/", carrier, tracking)
                        .retrieve()
                        .body(JsonNode.class);
        Rules.require(
                response != null
                        && carrier.equals(response.path("carrier").asText())
                        && tracking.equals(response.path("tracking_number").asText()),
                "Carrier tracking reference mismatch");
        var status = response.path("tracking_status");
        String code = status.path("status").asText();
        Instant at =
                status.hasNonNull("status_date")
                        ? Instant.parse(status.path("status_date").asText())
                        : null;
        return new Tracking(code, at);
    }
}

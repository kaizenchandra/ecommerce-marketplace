package com.example.marketplace.pricing;

import com.example.marketplace.shared.ProviderHttp;
import com.example.marketplace.shared.Rules;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "marketplace.tax.provider", havingValue = "stripe")
public class StripeTax implements TaxProvider {
    private final RestClient http;

    public StripeTax(
            @Value("${marketplace.tax.stripe-secret-key}") String key,
            @Value("${marketplace.tax.api-base:https://api.stripe.com}") String base,
            @Value("${marketplace.providers.timeout-ms:5000}") long timeout) {
        Rules.require(key.startsWith("sk_test_"), "Only Stripe Tax test credentials accepted");
        http = ProviderHttp.client(base, "https://api.stripe.com", "Bearer " + key, timeout);
    }

    public Calculation calculate(
            Map<String, Object> origin,
            Map<String, Object> destination,
            List<Line> lines,
            long shipping) {
        Rules.require(
                !origin.isEmpty() && origin.containsKey("country") && destination.containsKey("country"),
                "Seller origin and customer destination required for goods tax");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("currency", "inr");
        form.add("customer_details[address_source]", "shipping");
        form.add("expand[0]", "line_items");
        form.add("shipping_cost[amount]", Long.toString(shipping));
        form.add("shipping_cost[tax_behavior]", "exclusive");
        for (var field :
                Map.of(
                                "street1",
                                "line1",
                                "city",
                                "city",
                                "state",
                                "state",
                                "zip",
                                "postal_code",
                                "country",
                                "country")
                        .entrySet()) {
            if (destination.get(field.getKey()) != null)
                form.add(
                        "customer_details[address][" + field.getValue() + "]",
                        destination.get(field.getKey()).toString());
            if (origin.get(field.getKey()) != null)
                form.add(
                        "ship_from_details[address][" + field.getValue() + "]",
                        origin.get(field.getKey()).toString());
        }
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            Rules.require(
                    line.taxCode() != null && line.taxCode().matches("txcd_[0-9]+"),
                    "Product tax classification required");
            String prefix = "line_items[" + i + "]";
            form.add(prefix + "[amount]", Long.toString(line.net()));
            form.add(prefix + "[reference]", line.offerId().toString());
            form.add(prefix + "[tax_code]", line.taxCode());
            form.add(prefix + "[tax_behavior]", "exclusive");
        }
        JsonNode result =
                http.post()
                        .uri("/v1/tax/calculations")
                        .header("Stripe-Version", "2026-10-28.endive")
                        .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class);
        Rules.require(
                result != null
                        && result.has("livemode")
                        && !result.path("livemode").asBoolean()
                        && "inr".equals(result.path("currency").asText())
                        && !result.path("line_items").path("has_more").asBoolean(),
                "Invalid Stripe Tax calculation");
        Map<UUID, Long> taxes = new HashMap<>();
        long totalTax = 0, net = 0;
        for (JsonNode item : result.path("line_items").path("data")) {
            UUID ref = UUID.fromString(item.path("reference").asText());
            var original =
                    lines.stream().filter(line -> line.offerId().equals(ref)).findFirst().orElseThrow();
            long tax = item.path("amount_tax").asLong(-1);
            Rules.require(
                    tax >= 0
                            && item.path("amount").asLong(-1) == original.net()
                            && taxes.put(ref, tax) == null,
                    "Tax line mismatch");
            totalTax = Math.addExact(totalTax, tax);
            net = Math.addExact(net, original.net());
        }
        long shippingTax = result.path("shipping_cost").path("amount_tax").asLong(0);
        Rules.require(
                taxes.size() == lines.size()
                        && shippingTax >= 0
                        && totalTax + shippingTax == result.path("tax_amount_exclusive").asLong(-1)
                        && result.path("tax_amount_inclusive").asLong(0) == 0
                        && result.path("amount_total").asLong(-1) == net + shipping + totalTax + shippingTax,
                "Tax totals do not reconcile");
        String reference = result.path("id").asText();
        Instant expiry = Instant.ofEpochSecond(result.path("expires_at").asLong());
        Rules.require(
                reference.startsWith("taxcalc_") && expiry.isAfter(Instant.now()),
                "Tax calculation expired");
        return new Calculation(Map.copyOf(taxes), shippingTax, reference, expiry);
    }
}

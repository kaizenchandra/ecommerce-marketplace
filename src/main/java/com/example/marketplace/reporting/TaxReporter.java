package com.example.marketplace.reporting;

import com.fasterxml.jackson.databind.JsonNode;

public interface TaxReporter {
    JsonNode sale(String key, JsonNode request);

    JsonNode reversal(String key, JsonNode request, JsonNode original);

    JsonNode retrieve(String id);
}

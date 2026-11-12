package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Parses a listing given as JSON text, keeping its numbers exactly as written. */
final class ModelJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private ModelJson() {
    }

    /**
     * The JSON object in {@code json}, as a tree the SDK writes back unchanged.
     *
     * @throws IllegalArgumentException when the text is not exactly one JSON object (the message never quotes it)
     */
    static Object object(String json) {
        if (json == null) {
            throw new IllegalArgumentException("listing JSON is required");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("listing is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("listing must be a JSON object");
        }
        return node;
    }
}

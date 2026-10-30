package com.thanhdatpb.listingguard.rag.analysis;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the model's {@code {"findings": [...]}} (ADR-011 item 5). An item that does not match the schema is
 * dropped and counted, never repaired; an answer that is not that object at all throws
 * {@link InvalidLlmOutputException}. Only the first {@value #MAX_FINDINGS} items are looked at; the rest count
 * as invalid.
 */
public final class PolicyFindingParser {

    public static final int MAX_FINDINGS = 10;
    public static final int MAX_TEXT_LENGTH = 400;

    private static final String RULE_PREFIX = "policy.";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Set<String> topics;
    private final Set<String> fields;

    /**
     * @param topics allowed topics, so {@code ruleId} must be {@code policy.<topic>} for one of them
     * @param fields allowed values of {@code field}
     */
    public PolicyFindingParser(Set<String> topics, Set<String> fields) {
        if (topics.isEmpty() || fields.isEmpty()) {
            throw new IllegalArgumentException("topics and fields must not be empty");
        }
        this.topics = Set.copyOf(topics);
        this.fields = Set.copyOf(fields);
    }

    public ParseResult parse(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidLlmOutputException("empty answer");
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new InvalidLlmOutputException("answer is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidLlmOutputException("answer is not a JSON object");
        }
        JsonNode findings = root.get("findings");
        if (findings == null || !findings.isArray()) {
            throw new InvalidLlmOutputException("answer has no findings array");
        }
        List<ProposedFinding> valid = new ArrayList<>();
        int index = 0;
        for (JsonNode item : findings) {
            if (index++ >= MAX_FINDINGS) {
                continue;
            }
            parseItem(item).ifPresent(valid::add);
        }
        return new ParseResult(valid, findings.size());
    }

    private Optional<ProposedFinding> parseItem(JsonNode item) {
        if (!item.isObject()) {
            return Optional.empty();
        }
        String ruleId = text(item, "ruleId");
        if (ruleId == null || !ruleId.startsWith(RULE_PREFIX)
                || !topics.contains(ruleId.substring(RULE_PREFIX.length()))) {
            return Optional.empty();
        }
        Severity severity = severity(text(item, "severity"));
        String field = text(item, "field");
        if (severity == null || field == null || !fields.contains(field)) {
            return Optional.empty();
        }
        JsonNode valueNode = item.get("value");
        String value = null;
        if (valueNode == null) {
            return Optional.empty();
        }
        if (!valueNode.isNull()) {
            value = text(item, "value");
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
        }
        String message = text(item, "message");
        String suggestion = text(item, "suggestion");
        if (!validSentence(message) || !validSentence(suggestion)) {
            return Optional.empty();
        }
        JsonNode citation = item.get("citation");
        if (citation == null || !citation.isObject()) {
            return Optional.empty();
        }
        JsonNode chunkId = citation.get("chunkId");
        String quote = text(citation, "quote");
        if (chunkId == null || !chunkId.isIntegralNumber() || !chunkId.canConvertToLong() || quote == null) {
            return Optional.empty();
        }
        return Optional.of(new ProposedFinding(
                ruleId, severity, field, value, message, suggestion, chunkId.longValue(), quote));
    }

    private static String text(JsonNode object, String name) {
        JsonNode node = object.get(name);
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    private static Severity severity(String name) {
        if (name == null) {
            return null;
        }
        try {
            return Severity.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean validSentence(String s) {
        return s != null && !s.isBlank() && s.codePointCount(0, s.length()) <= MAX_TEXT_LENGTH;
    }
}

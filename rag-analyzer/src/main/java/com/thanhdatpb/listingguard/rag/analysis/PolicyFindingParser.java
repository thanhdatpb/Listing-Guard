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
 * {@link InvalidLlmOutputException}. Only the first {@code maxFindings} items are looked at; the rest count as
 * invalid. The limits come from the analyzer profile, not from this class.
 */
public final class PolicyFindingParser {

    private static final String RULE_PREFIX = "policy.";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .build();

    private final Set<String> topics;
    private final Set<String> fields;
    private final int maxFindings;
    private final int maxTextLength;
    private final boolean strict;
    private static final Set<String> ITEM_FIELDS = Set.of("ruleId", "severity", "field", "value", "message", "suggestion", "citation");
    private static final Set<String> CITATION_FIELDS = Set.of("chunkId", "quote", "start", "end", "sourceHash");

    /**
     * @param topics        allowed topics, so {@code ruleId} must be {@code policy.<topic>} for one of them
     * @param fields        allowed values of {@code field}
     * @param maxFindings   how many items of the answer are looked at, positive
     * @param maxTextLength most characters (code points) in {@code message} and in {@code suggestion}, positive
     */
    public PolicyFindingParser(Set<String> topics, Set<String> fields, int maxFindings, int maxTextLength) {
        this(topics, fields, maxFindings, maxTextLength, false);
    }

    PolicyFindingParser(Set<String> topics, Set<String> fields, int maxFindings, int maxTextLength, boolean strict) {
        if (topics.isEmpty() || fields.isEmpty()) {
            throw new IllegalArgumentException("topics and fields must not be empty");
        }
        if (maxFindings <= 0 || maxTextLength <= 0) {
            throw new IllegalArgumentException("maxFindings and maxTextLength must be positive");
        }
        this.topics = Set.copyOf(topics);
        this.fields = Set.copyOf(fields);
        this.maxFindings = maxFindings;
        this.maxTextLength = maxTextLength;
        this.strict = strict;
    }

    public ParseResult parse(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidLlmOutputException("empty answer");
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new InvalidLlmOutputException("answer is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidLlmOutputException("answer is not a JSON object");
        }
        JsonNode findings = root.get("findings");
        if (findings == null || !findings.isArray()) {
            throw new InvalidLlmOutputException("answer has no findings array");
        }
        if (strict && (root.size() != 1 || findings.size() > maxFindings)) {
            throw new InvalidLlmOutputException("answer is outside the policy finding schema");
        }
        List<ProposedFinding> valid = new ArrayList<>();
        int index = 0;
        for (JsonNode item : findings) {
            if (index++ >= maxFindings) {
                continue;
            }
            parseItem(item).ifPresent(valid::add);
        }
        return new ParseResult(valid, findings.size());
    }

    private Optional<ProposedFinding> parseItem(JsonNode item) {
        if (!item.isObject() || (strict && !hasFields(item, ITEM_FIELDS))) {
            return Optional.empty();
        }
        String ruleId = text(item, "ruleId");
        if (ruleId == null || !ruleId.startsWith(RULE_PREFIX)
                || !topics.contains(ruleId.substring(RULE_PREFIX.length()))) {
            return Optional.empty();
        }
        Severity severity = severity(text(item, "severity"));
        String field = text(item, "field");
        if (severity == null || (strict && severity != Severity.WARNING && severity != Severity.INFO) || field == null || !fields.contains(field)) {
            return Optional.empty();
        }
        JsonNode valueNode = item.get("value");
        String value = null;
        if (valueNode == null) {
            return Optional.empty();
        }
        if (!valueNode.isNull()) {
            value = text(item, "value");
            if (value == null || value.isBlank() || (strict && value.length() > maxTextLength)) {
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
        ProposedFinding.Coordinates coordinates = null;
        if (strict) {
            if (!hasFields(citation, CITATION_FIELDS) || chunkId.longValue() <= 0 || quote.length() > 800) {
                return Optional.empty();
            }
            JsonNode start = citation.get("start");
            JsonNode end = citation.get("end");
            String hash = text(citation, "sourceHash");
            if (!start.isIntegralNumber() || !start.canConvertToInt() || !end.isIntegralNumber() || !end.canConvertToInt()
                    || hash == null || !hash.matches("[0-9a-f]{64}")) {
                return Optional.empty();
            }
            coordinates = new ProposedFinding.Coordinates(start.intValue(), end.intValue(), hash);
        }
        return Optional.of(new ProposedFinding(
                ruleId, severity, field, value, message, suggestion, chunkId.longValue(), quote, coordinates));
    }

    private static boolean hasFields(JsonNode node, Set<String> names) {
        return node.size() == names.size() && names.stream().allMatch(node::has);
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

    private boolean validSentence(String s) {
        return s != null && !s.isBlank() && s.codePointCount(0, s.length()) <= maxTextLength;
    }
}

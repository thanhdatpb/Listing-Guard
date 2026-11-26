package com.thanhdatpb.listingguard.trademark.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/** Catalog provenance only. These signals do not participate in scoring. */
public record DistinctivenessSignals(
        @JsonProperty("word_count") Integer wordCount,
        @JsonProperty("same_mark_count") Long sameMarkCount,
        @JsonProperty("same_mark_class_count") Integer sameMarkClassCount,
        @JsonProperty("mark_kind") MarkKind markKind,
        @JsonProperty("dictionary_word") Boolean dictionaryWord,
        @JsonProperty("distinctiveness_tier") Tier tier,
        @JsonProperty("distinctiveness_reason") Reason reason,
        @JsonProperty("owner_count_same_mark") @JsonInclude(JsonInclude.Include.NON_NULL) Long ownerCountSameMark) {

    public DistinctivenessSignals(Integer words, Long serials, Integer classes, MarkKind kind, Boolean dictionaryWord,
                                  Tier tier, Reason reason) {
        this(words, serials, classes, kind, dictionaryWord, tier, reason, null);
    }

    public enum MarkKind { STANDARD_CHARACTER, WORDS_WITH_DESIGN, OTHER }
    public enum Tier { STRONG, ORDINARY, WEAK }
    public enum Reason { DICTIONARY_UNAVAILABLE, DRAWING_UNKNOWN, COMMON_OR_REPEATED, UNIQUE_MULTIWORD, DEFAULT,
        OWNERS_UNKNOWN, CROWDED_OWNERS, DICTIONARY_FEW_OWNERS, UNIQUE_OWNER_WORD }

    public static final Set<String> KEYS = Set.of("word_count", "same_mark_count", "same_mark_class_count",
            "mark_kind", "dictionary_word", "distinctiveness_tier", "distinctiveness_reason", "owner_count_same_mark");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static DistinctivenessSignals parse(JsonNode row) {
        Integer words = integer(row, "word_count", 300);
        Long serials = count(row, "same_mark_count", Long.MAX_VALUE);
        Integer classes = integer(row, "same_mark_class_count", 45);
        MarkKind kind = enumeration(row, "mark_kind", MarkKind.class);
        JsonNode dictionary = value(row, "dictionary_word");
        if (dictionary != null && !dictionary.isBoolean()) {
            throw new IllegalArgumentException("dictionary_word must be boolean or null");
        }
        Boolean word = dictionary == null ? null : dictionary.booleanValue();
        Tier tier = enumeration(row, "distinctiveness_tier", Tier.class);
        Reason reason = enumeration(row, "distinctiveness_reason", Reason.class);
        Long owners = count(row, "owner_count_same_mark", Long.MAX_VALUE);
        if (owners == null && words == null && serials == null && classes == null && kind == null && word == null && tier == null && reason == null) {
            return null;
        }
        return new DistinctivenessSignals(words, serials, classes, kind, word, tier, reason, owners);
    }

    public static DistinctivenessSignals fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return parse(JSON.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid catalog distinctiveness JSON", e);
        }
    }

    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write catalog distinctiveness JSON", e);
        }
    }

    private static JsonNode value(JsonNode row, String name) {
        JsonNode value = row.get(name);
        return value == null || value.isNull() ? null : value;
    }

    private static Integer integer(JsonNode row, String name, int max) {
        Long count = count(row, name, max);
        return count == null ? null : count.intValue();
    }

    private static Long count(JsonNode row, String name, long max) {
        JsonNode value = value(row, name);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1 || value.longValue() > max) {
            throw new IllegalArgumentException(name + " must be an integer from 1 to " + max);
        }
        return value.longValue();
    }

    private static <E extends Enum<E>> E enumeration(JsonNode row, String name, Class<E> type) {
        JsonNode value = value(row, name);
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.asText());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " has an unsupported value");
        }
    }
}

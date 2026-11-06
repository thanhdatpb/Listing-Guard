package com.thanhdatpb.listingguard.ruleengine;

import java.util.Objects;

/**
 * One number a rule enforces on one field of a listing, as the rule itself declares it through {@link Rule#limits}.
 *
 * <p>The value is read from the ruleset settings, never written in Java (ADR-001). {@code kind} says what is limited and
 * {@code unit} what the rule counts, exactly as its {@code evaluate} measures it: a rule that counts code points declares
 * {@link Unit#CODE_POINTS}, one that counts UTF-8 bytes declares {@link Unit#UTF8_BYTES}. A limit on a list field
 * ({@code tags}, {@code bullet_points}) is a {@link Kind#MAX_COUNT} of its items, or a {@link Kind#MAX_LENGTH} /
 * {@link Kind#MAX_BYTES} of each item.
 *
 * @param field the name of the field in {@code docs/input-schema.md}, such as {@code title} or {@code search_terms}
 */
public record Limit(String field, Kind kind, Unit unit, int value) {

    /** What is limited. */
    public enum Kind {
        /** The length of a text, or of each item of a list, in {@link Unit#CODE_POINTS}. */
        MAX_LENGTH,
        /** The number of items in a list. */
        MAX_COUNT,
        /** The size of a text, or of each item of a list, in {@link Unit#UTF8_BYTES}. */
        MAX_BYTES,
        /** How many times the same word or character may appear in a text. */
        MAX_OCCURRENCES
    }

    /** What a rule counts. */
    public enum Unit {
        /** Unicode code points: an emoji counts once. */
        CODE_POINTS,
        /** Items of a list. */
        ITEMS,
        /** Bytes of the UTF-8 encoding. */
        UTF8_BYTES,
        /** Occurrences of a word or a character. */
        OCCURRENCES
    }

    public Limit {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(unit, "unit");
    }

    /** A length counted in code points. */
    public static Limit maxLength(String field, int value) {
        return new Limit(field, Kind.MAX_LENGTH, Unit.CODE_POINTS, value);
    }

    /** A number of items in a list. */
    public static Limit maxCount(String field, int value) {
        return new Limit(field, Kind.MAX_COUNT, Unit.ITEMS, value);
    }

    /** A size counted in UTF-8 bytes. */
    public static Limit maxBytes(String field, int value) {
        return new Limit(field, Kind.MAX_BYTES, Unit.UTF8_BYTES, value);
    }

    /** How many times the same word or character may appear. */
    public static Limit maxOccurrences(String field, int value) {
        return new Limit(field, Kind.MAX_OCCURRENCES, Unit.OCCURRENCES, value);
    }
}

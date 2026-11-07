package com.thanhdatpb.listingguard.personalization.config;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules, their kinds and the placeholders their messages may use (ADR-016). A ruleset must declare every rule and
 * every kind listed here, once, and nothing else: the Java code of a rule and the ruleset are held together at load time.
 */
public final class RuleIds {

    public static final String UNSUPPORTED_CHARACTERS = "unsupported_characters";
    public static final String TOO_LONG = "too_long";
    public static final String WHITESPACE_ISSUE = "whitespace_issue";

    public static final String CHARACTER_NOT_ALLOWED = "character_not_allowed";
    public static final String LOWERCASE_NOT_PRINTED = "lowercase_not_printed";
    public static final String LEADING_SPACE = "leading_space";
    public static final String TRAILING_SPACE = "trailing_space";
    public static final String REPEATED_SPACE = "repeated_space";

    /** The character rules. */
    public static final List<String> RULES = List.of(UNSUPPORTED_CHARACTERS, TOO_LONG, WHITESPACE_ISSUE);

    /** The rule ids of the trademark scanner (ADR-009) whose findings this pack words itself. */
    public static final List<String> TRADEMARK_RULES = List.of("catalog_exact", "catalog_phrase", "catalog_fuzzy",
            "geo_sport_combination", "pure_color_description");

    static final Map<String, Set<String>> KINDS = Map.of(
            UNSUPPORTED_CHARACTERS, Set.of(CHARACTER_NOT_ALLOWED, LOWERCASE_NOT_PRINTED),
            TOO_LONG, Set.of(TOO_LONG),
            WHITESPACE_ISSUE, Set.of(LEADING_SPACE, TRAILING_SPACE, REPEATED_SPACE));

    private static final Set<String> COMMON = Set.of("subject", "profile");

    /** The placeholders a message or suggestion of a rule may use. */
    static final Map<String, Set<String>> PLACEHOLDERS = Map.of(
            UNSUPPORTED_CHARACTERS, union(COMMON, Set.of("chars", "codepoints", "position", "count")),
            TOO_LONG, union(COMMON, Set.of("length", "max_length", "excess", "position")),
            WHITESPACE_ISSUE, union(COMMON, Set.of("count", "position")));

    /** The placeholders of the trademark templates. */
    static final Set<String> TRADEMARK_PLACEHOLDERS = Set.of("subject", "from", "to", "mark", "score");

    private RuleIds() {
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }
}

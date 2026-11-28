package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many items a listing sells, as the seller writes it: {@code pack of 2}, {@code 4 pieces}, {@code set of six}. The expressions
 * ({@code patterns}, each with the number or the number word as its first group) and the number words are the ruleset's. A number that no
 * expression ties to a count ({@code 2 people}, a year, a size) is not read.
 */
public final class QuantityFacts implements FactExtractor {

    public static final String FAMILY = "quantity";

    private final List<Pattern> patterns;
    private final Map<String, String> words;
    private final Ambiguity ambiguity;

    private QuantityFacts(List<Pattern> patterns, Map<String, String> words, Ambiguity ambiguity) {
        this.patterns = patterns;
        this.words = words;
        this.ambiguity = ambiguity;
    }

    /** Reads {@code patterns}, {@code number_words} and the ambiguity keys of {@code settings}. */
    public static QuantityFacts from(RuleSettings settings) {
        List<Pattern> patterns = settings.stringListParam("patterns").stream()
                .map(regex -> Regexes.compile(settings.ruleId(), regex, true)).toList();
        for (Pattern pattern : patterns) {
            if (pattern.matcher("").groupCount() < 1) {
                throw new com.thanhdatpb.listingguard.ruleengine.config.RulesetException(
                        "Rule '" + settings.ruleId() + "': '" + pattern.pattern() + "' needs a group for the number");
            }
        }
        Map<String, String> words = new java.util.HashMap<>();
        settings.stringMapParam("number_words").forEach((word, number) -> words.put(word.toLowerCase(Locale.ROOT), number.trim()));
        return new QuantityFacts(patterns, Map.copyOf(words), Ambiguity.from(settings));
    }

    @Override
    public List<Mention> extract(String text) {
        List<Mention> mentions = new ArrayList<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String raw = matcher.group(1);
                if (raw == null) {
                    continue;
                }
                String number = words.getOrDefault(raw.toLowerCase(Locale.ROOT), raw);
                if (!number.matches("\\d{1,4}") || Integer.parseInt(number) == 0) {
                    continue;
                }
                String key = Integer.toString(Integer.parseInt(number));
                if (ambiguity.around(text, matcher.start(), matcher.end())
                        || mentions.stream().anyMatch(m -> m.start() < matcher.end() && matcher.start() < m.end())) {
                    continue;
                }
                mentions.add(new Mention(FAMILY, key, matcher.group(), matcher.start(), matcher.end()));
            }
        }
        mentions.sort(java.util.Comparator.comparingInt(Mention::start));
        return mentions;
    }
}

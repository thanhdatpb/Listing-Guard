package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sizes written as a number and a unit ({@code 11oz}, {@code 11 oz}, {@code 12 x 16 inch}). The units and their spellings, the units that
 * may carry a second dimension, and the words that make a number something other than a size ({@code weighs 8 oz}) are the ruleset's.
 * The family of a mention is its unit, so {@code 11 oz} and {@code 15 oz} differ and {@code 11 oz} and {@code 6 in} are never compared.
 * A size with no unit ({@code size 11}, {@code M}) is not read: guessing it would turn ordinary numbers into findings.
 */
public final class SizeFacts implements FactExtractor {

    private static final String NUMBER = "(\\d{1,4}(?:[.,]\\d{1,2})?)";

    private final Pattern pattern;
    private final Map<String, String> unitOf;
    private final Set<String> dimensional;
    private final Pattern ignore;
    private final int ignoreBefore;
    private final int ignoreAfter;
    private final Ambiguity ambiguity;

    private SizeFacts(Pattern pattern, Map<String, String> unitOf, Set<String> dimensional, Pattern ignore, int ignoreBefore,
                      int ignoreAfter, Ambiguity ambiguity) {
        this.pattern = pattern;
        this.unitOf = unitOf;
        this.dimensional = dimensional;
        this.ignore = ignore;
        this.ignoreBefore = ignoreBefore;
        this.ignoreAfter = ignoreAfter;
        this.ambiguity = ambiguity;
    }

    /** Reads {@code units}, {@code dimension_units}, {@code ignore_words}, {@code ignore_before}, {@code ignore_after} and the ambiguity keys of {@code settings}. */
    public static SizeFacts from(RuleSettings settings) {
        Map<String, String> unitOf = new HashMap<>();
        List<String> forms = new ArrayList<>();
        settings.stringListMapParam("units").forEach((unit, spellings) -> spellings.forEach(spelling -> {
            unitOf.put(fold(spelling), unit);
            forms.add(spelling);
        }));
        forms.sort(Comparator.comparingInt(String::length).reversed());
        StringBuilder alternation = new StringBuilder();
        for (String form : forms) {
            alternation.append(alternation.length() == 0 ? "" : "|");
            String[] parts = form.trim().split("\\s+");
            for (int i = 0; i < parts.length; i++) {
                alternation.append(i > 0 ? "\\s?" : "").append(Pattern.quote(parts[i]));
            }
        }
        Pattern pattern = Regexes.compile(settings.ruleId(), "(?<![\\p{L}\\p{N}.,])" + NUMBER + "(?:\\s?[x×]\\s?" + NUMBER + ")?\\s?("
                + alternation + ")(?![\\p{L}\\p{N}])", true);
        Set<String> dimensional = Set.copyOf(settings.stringListParam("dimension_units"));
        List<String> words = settings.stringListParam("ignore_words");
        Pattern ignore = Regexes.compile(settings.ruleId(), words.isEmpty() ? "(?!)" : words.stream().map(Regexes::phrase)
                .collect(java.util.stream.Collectors.joining("|")), true);
        Ambiguity ambiguity = Ambiguity.from(settings);
        return new SizeFacts(pattern, unitOf, dimensional, ignore, Math.max(0, settings.intParam("ignore_before")),
                Math.max(0, settings.intParam("ignore_after")), ambiguity);
    }

    @Override
    public List<Mention> extract(String text) {
        List<Mention> mentions = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String unit = unitOf.get(fold(matcher.group(3)));
            if (unit == null) {
                continue;
            }
            String second = matcher.group(2);
            if (second != null && !dimensional.contains(unit)) {
                // "2 x 11oz" is a count and a size, not a rectangle: the size is the second number
                add(mentions, text, unit, number(second) + " " + unit, matcher.start(2), matcher.end());
                continue;
            }
            String key = number(matcher.group(1)) + (second == null ? "" : "x" + number(second)) + " " + unit;
            add(mentions, text, unit, key, matcher.start(), matcher.end());
        }
        return mentions;
    }

    private void add(List<Mention> mentions, String text, String unit, String key, int start, int end) {
        String around = text.substring(Math.max(0, start - ignoreBefore), start) + " " + text.substring(end, Math.min(text.length(), end + ignoreAfter));
        if (ignore.matcher(around).find() || ambiguity.around(text, start, end)) {
            return;
        }
        mentions.add(new Mention(unit, key, text.substring(start, end), start, end));
    }

    private static String number(String raw) {
        return new BigDecimal(raw.replace(',', '.')).stripTrailingZeros().toPlainString();
    }

    private static String fold(String text) {
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}

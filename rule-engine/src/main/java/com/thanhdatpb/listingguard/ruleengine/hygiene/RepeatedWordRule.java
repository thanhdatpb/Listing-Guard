package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * The same word twice in a row, a copy-paste slip ({@code mug mug}, {@code the the}). Only words of at least {@code min_length} letters
 * separated by blanks on one line count, ignoring case; words of {@code exempt_words} that are repeated on purpose ({@code Mom Mom}, {@code no no})
 * are not reported. A comma, a hyphen or a number between them is no repeat. This is not {@code title_repeated_words}, which counts a word anywhere in
 * the title. No fix: which of the two to drop is the seller's.
 */
public final class RepeatedWordRule extends TextRule {

    public static final String ID = "repeated_word";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.stringListParam("exempt_words");
        regex(settings);
    }

    private static java.util.regex.Pattern regex(RuleSettings settings) {
        int min = Math.max(1, settings.intParam("min_length"));
        return Regexes.compile(ID, "(?<![\\p{L}\\p{N}])(\\p{L}{" + min + ",})[ \\t]+\\1(?![\\p{L}\\p{N}])", true);
    }

    @Override
    List<Hit> scan(String field, String text, RuleSettings settings) {
        Set<String> exempt = new java.util.HashSet<>();
        settings.stringListParam("exempt_words").forEach(word -> exempt.add(word.toLowerCase(Locale.ROOT)));
        Matcher matcher = regex(settings).matcher(text);
        List<Hit> hits = new ArrayList<>();
        while (matcher.find()) {
            String word = matcher.group(1).toLowerCase(Locale.ROOT);
            if (exempt.contains(word)) {
                continue;
            }
            hits.add(new Hit(matcher.group(), matcher.start(), matcher.end(), "Từ " + quote(matcher.group(1)) + " lặp liền nhau trong " + field + ": "
                    + quote(matcher.group()) + ".", "Bỏ một trong hai từ lặp."));
        }
        return hits;
    }
}

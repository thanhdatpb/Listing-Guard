package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import java.util.List;
import java.util.regex.Pattern;

/**
 * "Available in 11oz or 15oz" lists options: a value in such a sentence says nothing about which one this listing is. A mention that
 * has one of the {@code ambiguity_markers} (whole words or phrases, ignoring case) within {@code ambiguity_before} characters before it or
 * {@code ambiguity_after} after it is left out. The words and the two numbers are the ruleset's.
 */
final class Ambiguity {

    private final List<Pattern> markers;
    private final int before;
    private final int after;

    private Ambiguity(List<Pattern> markers, int before, int after) {
        this.markers = markers;
        this.before = before;
        this.after = after;
    }

    static void validate(RuleSettings settings) {
        from(settings);
    }

    static Ambiguity from(RuleSettings settings) {
        List<Pattern> patterns = settings.stringListParam("ambiguity_markers").stream()
                .map(marker -> Regexes.compile(settings.ruleId(), Regexes.phrase(marker), true)).toList();
        return new Ambiguity(patterns, Math.max(0, settings.intParam("ambiguity_before")), Math.max(0, settings.intParam("ambiguity_after")));
    }

    boolean around(String text, int start, int end) {
        String window = text.substring(Math.max(0, start - before), Math.min(text.length(), end + after));
        for (Pattern marker : markers) {
            if (marker.matcher(window).find()) {
                return true;
            }
        }
        return false;
    }
}

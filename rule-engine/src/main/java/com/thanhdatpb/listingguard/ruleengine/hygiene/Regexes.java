package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Compiles the regular expressions of a ruleset once. A bad expression fails at startup, through {@code validate}, not on a request. */
final class Regexes {

    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private Regexes() {
    }

    /** @param ignoreCase true for a case-insensitive, Unicode-aware expression */
    static Pattern compile(String ruleId, String regex, boolean ignoreCase) {
        String key = (ignoreCase ? "i:" : "c:") + regex;
        Pattern cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Pattern pattern = Pattern.compile(regex, ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
            CACHE.put(key, pattern);
            return pattern;
        } catch (PatternSyntaxException e) {
            throw new RulesetException("Rule '" + ruleId + "': '" + regex + "' is not a valid regular expression: " + e.getDescription());
        }
    }

    /** A literal form (a word or a phrase) as an expression: blanks match any run of blanks, and the form is a whole word on both sides. */
    static String phrase(String form) {
        StringBuilder out = new StringBuilder("(?<![\\p{L}\\p{N}])");
        String[] parts = form.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            out.append(i > 0 ? "\\s+" : "").append(Pattern.quote(parts[i]));
        }
        return out.append("(?![\\p{L}\\p{N}])").toString();
    }
}

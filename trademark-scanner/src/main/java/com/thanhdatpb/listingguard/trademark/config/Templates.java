package com.thanhdatpb.listingguard.trademark.config;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Message and suggestion templates with {@code {name}} placeholders, checked when the ruleset loads. */
public final class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private Templates() {
    }

    /** Fails when {@code text} uses a placeholder that is not in {@code allowed} or has a stray brace. */
    static void validate(String where, String text, Set<String> allowed) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) {
                throw new TrademarkRulesetException(where + " uses unknown placeholder '{" + matcher.group(1)
                        + "}' (allowed: " + allowed.stream().sorted().toList() + ")");
            }
        }
        String withoutPlaceholders = PLACEHOLDER.matcher(text).replaceAll("");
        if (withoutPlaceholders.indexOf('{') >= 0 || withoutPlaceholders.indexOf('}') >= 0) {
            throw new TrademarkRulesetException(where + " has a stray '{' or '}'");
        }
    }

    /** Replaces every {@code {name}} with its value. The values are inserted as they are, never re-scanned. */
    public static String fill(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A placeholder the seller meant to replace and did not ({@code [NAME]}, {@code {name}}, {@code XXXXX}, {@code TBD}, {@code lorem ipsum}). The
 * expressions are the ruleset's: {@code patterns} ignore case, {@code case_sensitive_patterns} do not (an upper-case {@code TBD}), and at most
 * {@code report_at_most} matches of a field are reported. A word that merely resembles one ({@code Name necklace}, {@code XXXL}) has no brackets
 * or no run of X and is not a match. No fix: the right value is the seller's.
 */
public final class PlaceholderLeftoverRule extends TextRule {

    public static final String ID = "placeholder_leftover";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        patterns(settings);
        settings.intParam("report_at_most");
    }

    private static List<Pattern> patterns(RuleSettings settings) {
        List<Pattern> patterns = new ArrayList<>();
        settings.stringListParam("patterns").forEach(regex -> patterns.add(Regexes.compile(ID, regex, true)));
        settings.stringListParam("case_sensitive_patterns").forEach(regex -> patterns.add(Regexes.compile(ID, regex, false)));
        return patterns;
    }

    @Override
    List<Hit> scan(String field, String text, RuleSettings settings) {
        List<int[]> spans = new ArrayList<>();
        for (Pattern pattern : patterns(settings)) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                if (matcher.end() > matcher.start()) {
                    spans.add(new int[] {matcher.start(), matcher.end()});
                }
            }
        }
        spans.sort(java.util.Comparator.comparingInt((int[] s) -> s[0]).thenComparingInt(s -> -s[1]));
        List<Hit> hits = new ArrayList<>();
        int covered = -1;
        int max = settings.intParam("report_at_most");
        for (int[] span : spans) {
            if (span[0] < covered || hits.size() >= max) {
                continue;
            }
            covered = span[1];
            String value = text.substring(span[0], span[1]);
            hits.add(new Hit(value, span[0], span[1], "Còn chỗ giữ chỗ chưa thay trong " + field + ": " + quote(value) + ".",
                    "Thay " + quote(value) + " bằng nội dung thật hoặc bỏ đi."));
        }
        return hits;
    }
}

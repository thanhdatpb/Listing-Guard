package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.core.finding.FixSuggestion;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blanks that were not meant: a run of more than {@code spaces_allowed} spaces between words, a character of {@code invisible_chars} (a tab, a
 * no-break space) or of {@code zero_width_chars} and a blank before one of {@code space_before}. A line break of a description and the blanks at
 * the two ends of a text (the input is trimmed before any rule) are not reported. The first problem of a field is reported, with how many
 * there are.
 *
 * <p>This is the one rule of ADR-039 that proposes a fix, because the fix is certain: for a title or a tag the cleaned text (runs collapsed to one
 * space, zero-width characters removed, the other invisible characters made spaces, no blank before the listed marks) is proposed, and
 * {@code RuleEngine} drops it unless it removes the finding and adds none. For any other field the advice is a sentence. With a fix the
 * finding's value is the whole text (what the fix replaces) and the span marks the first problem.
 */
public final class WhitespaceRule extends TextRule {

    public static final String ID = "whitespace";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        settings.intParam("spaces_allowed");
        settings.stringListParam("invisible_chars");
        settings.stringListParam("space_before");
        settings.stringListParam("zero_width_chars");
        settings.stringListParam("fix_fields");
        problems(settings);
    }

    private static Pattern problems(RuleSettings settings) {
        int max = Math.max(1, settings.intParam("spaces_allowed"));
        StringBuilder invisible = new StringBuilder();
        settings.stringListParam("invisible_chars").forEach(c -> invisible.append(Pattern.quote(c)).append('|'));
        settings.stringListParam("zero_width_chars").forEach(c -> invisible.append(Pattern.quote(c)).append('|'));
        StringBuilder before = new StringBuilder();
        settings.stringListParam("space_before").forEach(c -> before.append(Pattern.quote(c)).append('|'));
        String regex = "(?<=\\S) {" + (max + 1) + ",}(?=\\S)|" + invisible + (before.length() == 0 ? "" : "(?<=\\S) +(?:" + before.substring(0, before.length() - 1) + ")|") + "(?!)";
        return Regexes.compile(ID, regex, false);
    }

    @Override
    List<Hit> scan(String field, String text, RuleSettings settings) {
        Matcher matcher = problems(settings).matcher(text);
        int count = 0;
        int start = -1;
        int end = -1;
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) {
                continue;
            }
            count++;
            if (start < 0) {
                start = matcher.start();
                end = matcher.end();
            }
        }
        if (count == 0) {
            return List.of();
        }
        String message = "Khoảng trắng thừa hoặc ký tự trắng ẩn trong " + field + (count > 1 ? " (" + count + " chỗ)" : "") + ".";
        FixSuggestion fix = null;
        if (settings.stringListParam("fix_fields").contains(base(field))) {
            String cleaned = clean(text, settings);
            if (!cleaned.isBlank() && !cleaned.equals(text)) {
                fix = FixSuggestion.replace("Bỏ khoảng trắng thừa trong " + field + ": giữ một dấu cách giữa các từ, bỏ ký tự trắng ẩn.", cleaned);
            }
        }
        String suggestion = "Bỏ khoảng trắng thừa trong " + field + ": giữ một dấu cách giữa các từ.";
        if (fix != null) {
            return List.of(new Hit(text, start, end, message, suggestion, fix));
        }
        return List.of(new Hit(text.substring(start, end), start, end, message, suggestion));
    }

    private static String base(String field) {
        int bracket = field.indexOf('[');
        return bracket < 0 ? field : field.substring(0, bracket);
    }

    /** The text with the blanks the rule reports removed; deterministic and idempotent. */
    static String clean(String text, RuleSettings settings) {
        String cleaned = text;
        for (String zero : settings.stringListParam("zero_width_chars")) {
            cleaned = cleaned.replace(zero, "");
        }
        for (String invisible : settings.stringListParam("invisible_chars")) {
            cleaned = cleaned.replace(invisible, " ");
        }
        for (String mark : settings.stringListParam("space_before")) {
            cleaned = cleaned.replaceAll(" +" + Pattern.quote(mark), java.util.regex.Matcher.quoteReplacement(mark));
        }
        return cleaned.replaceAll("(?<=\\S) {2,}(?=\\S)", " ").strip();
    }
}

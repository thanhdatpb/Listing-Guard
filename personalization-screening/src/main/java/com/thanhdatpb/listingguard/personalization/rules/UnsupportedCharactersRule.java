package com.thanhdatpb.listingguard.personalization.rules;

import com.thanhdatpb.listingguard.personalization.TextFinding;
import com.thanhdatpb.listingguard.personalization.Texts;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset.PrintProfile;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset.RuleSpec;
import com.thanhdatpb.listingguard.personalization.config.RuleIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@code unsupported_characters} (ADR-016): a run of consecutive code points that are not in the print profile's classes is
 * one finding {@code character_not_allowed}; when the profile prints capitals only, a run of allowed lowercase letters is
 * one finding {@code lowercase_not_printed}. A character that is not allowed is never also reported as lowercase. The
 * offsets are UTF-16 code units in the value's text, the position in the message counts characters (code points), and
 * the evidence is the run cut to {@code evidence.max_value_chars}. Nothing is changed or corrected.
 */
public final class UnsupportedCharactersRule implements TextRule {

    private final RuleSpec spec;

    public UnsupportedCharactersRule(RuleSpec spec) {
        this.spec = spec;
    }

    @Override
    public String id() {
        return spec.id();
    }

    @Override
    public List<TextFinding> apply(RuleContext context) {
        List<TextFinding> findings = new ArrayList<>();
        PrintProfile profile = context.profile();
        for (int index = 0; index < context.values().size(); index++) {
            String text = context.values().get(index).text();
            String runKind = null;
            int runStart = 0;
            int runStartCodePoint = 0;
            int codePointIndex = 0;
            int offset = 0;
            while (offset < text.length()) {
                int codePoint = text.codePointAt(offset);
                String kind = classify(profile, codePoint);
                if (!Objects.equals(kind, runKind)) {
                    if (runKind != null) {
                        findings.add(finding(context, index, runKind, text, runStart, offset, runStartCodePoint,
                                codePointIndex - runStartCodePoint));
                    }
                    runKind = kind;
                    runStart = offset;
                    runStartCodePoint = codePointIndex;
                }
                offset += Character.charCount(codePoint);
                codePointIndex++;
            }
            if (runKind != null) {
                findings.add(finding(context, index, runKind, text, runStart, text.length(), runStartCodePoint,
                        codePointIndex - runStartCodePoint));
            }
        }
        return findings;
    }

    /** The kind of finding a code point belongs to, or null when the profile prints it as typed. */
    private static String classify(PrintProfile profile, int codePoint) {
        if (!profile.allows(codePoint)) {
            return RuleIds.CHARACTER_NOT_ALLOWED;
        }
        if (!profile.allowLowercase() && Character.isLowerCase(codePoint)) {
            return RuleIds.LOWERCASE_NOT_PRINTED;
        }
        return null;
    }

    private TextFinding finding(RuleContext context, int index, String kind, String text, int start, int end,
                                int startCodePoint, int count) {
        int limit = context.ruleset().maxValueChars();
        String kept = Texts.cut(text.substring(start, end), limit);
        boolean truncated = count > limit;
        String value = Texts.printable(kept);
        String codePoints = kept.codePoints().mapToObj(codePoint -> String.format("U+%04X", codePoint))
                .collect(Collectors.joining(" ")) + (truncated ? " …" : "");
        return Findings.of(spec, kind, context, index, start, end, value, Map.of(
                "chars", value + (truncated ? "…" : ""),
                "codepoints", codePoints,
                "position", String.valueOf(startCodePoint + 1),
                "count", String.valueOf(count)));
    }
}

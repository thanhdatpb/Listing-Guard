package com.thanhdatpb.listingguard.personalization.rules;

import com.thanhdatpb.listingguard.personalization.TextFinding;
import com.thanhdatpb.listingguard.personalization.Texts;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset.RuleSpec;
import com.thanhdatpb.listingguard.personalization.config.RuleIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code whitespace_issue} (ADR-016): whitespace at the start of the text ({@code leading_space}), at its end
 * ({@code trailing_space}), or two or more whitespace characters in a row inside it ({@code repeated_space}, one finding
 * per run; a run that touches an end is reported as leading or trailing only). Whitespace is {@link Texts#isWhitespace}. It
 * only reports: the text is never changed, and no text is kept as evidence.
 */
public final class WhitespaceIssueRule implements TextRule {

    private final RuleSpec spec;

    public WhitespaceIssueRule(RuleSpec spec) {
        this.spec = spec;
    }

    @Override
    public String id() {
        return spec.id();
    }

    @Override
    public List<TextFinding> apply(RuleContext context) {
        List<TextFinding> findings = new ArrayList<>();
        for (int index = 0; index < context.values().size(); index++) {
            scan(context, index, context.values().get(index).text(), findings);
        }
        return findings;
    }

    private void scan(RuleContext context, int index, String text, List<TextFinding> findings) {
        int length = text.length();
        int leadEnd = 0;
        while (leadEnd < length && Texts.isWhitespace(text.codePointAt(leadEnd))) {
            leadEnd += Character.charCount(text.codePointAt(leadEnd));
        }
        if (leadEnd > 0) {
            findings.add(finding(context, index, RuleIds.LEADING_SPACE, text, 0, leadEnd));
        }
        if (leadEnd == length) {
            return;
        }
        int trailStart = length;
        while (trailStart > leadEnd && Texts.isWhitespace(text.codePointBefore(trailStart))) {
            trailStart -= Character.charCount(text.codePointBefore(trailStart));
        }
        if (trailStart < length) {
            findings.add(finding(context, index, RuleIds.TRAILING_SPACE, text, trailStart, length));
        }
        int offset = leadEnd;
        while (offset < trailStart) {
            int codePoint = text.codePointAt(offset);
            if (!Texts.isWhitespace(codePoint)) {
                offset += Character.charCount(codePoint);
                continue;
            }
            int runEnd = offset;
            int count = 0;
            while (runEnd < trailStart && Texts.isWhitespace(text.codePointAt(runEnd))) {
                runEnd += Character.charCount(text.codePointAt(runEnd));
                count++;
            }
            if (count >= 2) {
                findings.add(finding(context, index, RuleIds.REPEATED_SPACE, text, offset, runEnd));
            }
            offset = runEnd;
        }
    }

    private TextFinding finding(RuleContext context, int index, String kind, String text, int start, int end) {
        return Findings.of(spec, kind, context, index, start, end, null, Map.of(
                "count", String.valueOf(text.codePointCount(start, end)),
                "position", String.valueOf(text.codePointCount(0, start) + 1)));
    }
}

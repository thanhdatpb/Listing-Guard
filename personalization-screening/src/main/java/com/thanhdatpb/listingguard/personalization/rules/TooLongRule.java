package com.thanhdatpb.listingguard.personalization.rules;

import com.thanhdatpb.listingguard.personalization.TextFinding;
import com.thanhdatpb.listingguard.personalization.Texts;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset.RuleSpec;
import com.thanhdatpb.listingguard.personalization.config.RuleIds;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code too_long} (ADR-016): the text has more code points than the print profile allows for its label (or the profile's
 * {@code max_length}). Spaces count. One finding per value; {@code start} is the offset of the first code point over the
 * limit and {@code end} the end of the text. No text is kept as evidence (the value is null) and nothing is shortened.
 */
public final class TooLongRule implements TextRule {

    private final RuleSpec spec;

    public TooLongRule(RuleSpec spec) {
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
            var value = context.values().get(index);
            String text = value.text();
            int limit = context.profile().limitFor(Texts.labelKey(value.label()));
            int length = text.codePointCount(0, text.length());
            if (length > limit) {
                int start = text.offsetByCodePoints(0, limit);
                findings.add(Findings.of(spec, RuleIds.TOO_LONG, context, index, start, text.length(), null, Map.of(
                        "length", String.valueOf(length),
                        "max_length", String.valueOf(limit),
                        "excess", String.valueOf(length - limit),
                        "position", String.valueOf(limit + 1))));
            }
        }
        return findings;
    }
}

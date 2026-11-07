package com.thanhdatpb.listingguard.personalization.rules;

import com.thanhdatpb.listingguard.personalization.TextFinding;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset.Kind;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset.RuleSpec;
import com.thanhdatpb.listingguard.personalization.config.Templates;
import java.util.HashMap;
import java.util.Map;

/** Builds a {@link TextFinding} from a rule's kind in the ruleset: severity and wording come from the file. */
final class Findings {

    private Findings() {
    }

    /**
     * @param value        the offending characters to keep as evidence, already printable and bounded, or null for none
     * @param placeholders the rule's own placeholders; {@code subject} and {@code profile} are added here
     */
    static TextFinding of(RuleSpec spec, String kindName, RuleContext context, int index, int start, int end, String value,
                          Map<String, String> placeholders) {
        Kind kind = spec.kind(kindName);
        Map<String, String> values = new HashMap<>(placeholders);
        values.put("subject", context.subject(index));
        values.put("profile", context.profile().code());
        return new TextFinding(spec.id(), kindName, kind.severity(), spec.source().status(), index, context.field(index),
                context.label(index), start, end, value, Templates.fill(kind.message(), values),
                Templates.fill(kind.suggestion(), values));
    }
}

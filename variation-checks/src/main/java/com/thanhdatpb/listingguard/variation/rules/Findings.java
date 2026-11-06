package com.thanhdatpb.listingguard.variation.rules;

import com.thanhdatpb.listingguard.variation.Texts;
import com.thanhdatpb.listingguard.variation.VariationFinding;
import com.thanhdatpb.listingguard.variation.config.Templates;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the findings of one rule from its ruleset entry: the kind's severity, message and suggestion, the rule's source
 * status, and the client text cut to the ruleset's limit, both in {@code value} and inside the message (the message
 * quotes client text, and what is stored must stay bounded).
 */
final class Findings {

    private final VariationRuleset.RuleSpec spec;
    private final int maxChars;

    Findings(VariationRuleset ruleset, String ruleId) {
        this.spec = ruleset.rule(ruleId);
        this.maxChars = ruleset.maxValueChars();
    }

    VariationRuleset.RuleSpec spec() {
        return spec;
    }

    VariationFinding make(String kind, String field, String value, List<String> relatedFields,
                          Map<String, String> placeholders) {
        VariationRuleset.Kind template = spec.kind(kind);
        Map<String, String> cut = new LinkedHashMap<>();
        placeholders.forEach((key, text) -> cut.put(key, Texts.cut(text, maxChars)));
        return new VariationFinding(spec.id(), kind, template.severity(), spec.source().status(), field,
                Texts.cut(value, maxChars), relatedFields, Templates.fill(template.message(), cut),
                Templates.fill(template.suggestion(), cut));
    }

    /** {@code values("a", "1", "b", "2")} is the map {@code a=1, b=2}, in that order. */
    static Map<String, String> values(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /** {@code children[2].attributes.size}, with the attribute name when there is one. */
    static String child(int index, String suffix) {
        return "children[" + index + "]" + (suffix == null ? "" : "." + suffix);
    }

    static String position(int index) {
        return String.valueOf(index + 1);
    }
}

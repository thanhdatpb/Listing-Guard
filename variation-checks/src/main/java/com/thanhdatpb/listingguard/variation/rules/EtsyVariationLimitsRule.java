package com.thanhdatpb.listingguard.variation.rules;

import static com.thanhdatpb.listingguard.variation.rules.Findings.values;

import com.thanhdatpb.listingguard.variation.Texts;
import com.thanhdatpb.listingguard.variation.VariationFinding;
import com.thanhdatpb.listingguard.variation.VariationInput;
import com.thanhdatpb.listingguard.variation.config.RuleIds;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Etsy (ADR-015): more variation types than the ruleset allows, a property that cannot be a variation, and more
 * combinations than can have a photo (an INFO reminder). Variation types are counted by distinct name, ignoring case;
 * the combinations are the children sent.
 */
final class EtsyVariationLimitsRule implements VariationRule {

    private final VariationRuleset.EtsyLimits limits;
    private final Findings findings;

    EtsyVariationLimitsRule(VariationRuleset ruleset) {
        this.limits = ruleset.etsyLimits();
        this.findings = new Findings(ruleset, RuleIds.ETSY_VARIATION_LIMITS);
    }

    @Override
    public String id() {
        return RuleIds.ETSY_VARIATION_LIMITS;
    }

    @Override
    public Set<String> marketplaces() {
        return findings.spec().marketplaces();
    }

    @Override
    public List<VariationFinding> apply(RuleContext context) {
        VariationInput input = context.input();
        List<VariationFinding> out = new ArrayList<>();
        List<String> properties = input.variationProperties();
        List<String> distinct = RuleContext.distinct(properties);

        if (distinct.size() > limits.maxVariationTypes()) {
            out.add(findings.make(RuleIds.TOO_MANY_VARIATION_TYPES, "variation_properties",
                    String.valueOf(distinct.size()), List.of(), values("count", String.valueOf(distinct.size()),
                            "limit", String.valueOf(limits.maxVariationTypes()), "properties", String.join(", ", distinct))));
        }
        Set<String> unsupported = new HashSet<>();
        limits.unsupportedProperties().forEach(name -> unsupported.add(Texts.fold(name)));
        Set<String> reported = new HashSet<>();
        for (int i = 0; i < properties.size(); i++) {
            String property = properties.get(i);
            if (unsupported.contains(Texts.fold(property)) && reported.add(Texts.fold(property))) {
                out.add(findings.make(RuleIds.VARIATION_TYPE_NOT_SUPPORTED, "variation_properties[" + i + "]", property,
                        List.of(), values("property", property, "unsupported",
                                String.join(", ", limits.unsupportedProperties()))));
            }
        }
        int combinations = input.children().size();
        if (combinations > limits.photoVariationLimit()) {
            out.add(findings.make(RuleIds.MANY_VARIATIONS_PHOTO_LIMIT, "children", String.valueOf(combinations),
                    List.of(), values("count", String.valueOf(combinations), "limit",
                            String.valueOf(limits.photoVariationLimit()))));
        }
        return out;
    }
}

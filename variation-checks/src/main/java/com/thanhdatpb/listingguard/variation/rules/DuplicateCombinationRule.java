package com.thanhdatpb.listingguard.variation.rules;

import static com.thanhdatpb.listingguard.variation.rules.Findings.child;
import static com.thanhdatpb.listingguard.variation.rules.Findings.position;
import static com.thanhdatpb.listingguard.variation.rules.Findings.values;

import com.thanhdatpb.listingguard.variation.Texts;
import com.thanhdatpb.listingguard.variation.VariationFinding;
import com.thanhdatpb.listingguard.variation.VariationInput;
import com.thanhdatpb.listingguard.variation.config.RuleIds;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Two children with the same value for every attribute of the theme (ADR-015). Values are compared ignoring case. A child
 * that lacks an attribute of the theme has no combination and is skipped; when the attributes of the theme are not known
 * the rule says nothing. One finding for each later child, naming the first.
 */
final class DuplicateCombinationRule implements VariationRule {

    private final Findings findings;

    DuplicateCombinationRule(VariationRuleset ruleset) {
        this.findings = new Findings(ruleset, RuleIds.DUPLICATE_COMBINATION);
    }

    @Override
    public String id() {
        return RuleIds.DUPLICATE_COMBINATION;
    }

    @Override
    public Set<String> marketplaces() {
        return findings.spec().marketplaces();
    }

    @Override
    public List<VariationFinding> apply(RuleContext context) {
        List<String> attributes = context.themeAttributes();
        if (attributes.isEmpty()) {
            return List.of();
        }
        List<VariationFinding> out = new ArrayList<>();
        Map<String, Integer> firstSeen = new HashMap<>();
        List<VariationInput.Child> children = context.input().children();
        for (int i = 0; i < children.size(); i++) {
            List<String> combination = combination(children.get(i), attributes);
            if (combination == null) {
                continue;
            }
            String key = combination.stream().map(Texts::fold).collect(Collectors.joining("\u0000"));
            Integer first = firstSeen.putIfAbsent(key, i);
            if (first != null) {
                String text = String.join(" / ", combination);
                out.add(findings.make(RuleIds.DUPLICATE_COMBINATION, child(i, null), text, List.of(child(first, null)),
                        values("position", position(i), "first_position", position(first), "combination", text)));
            }
        }
        return out;
    }

    /** The child's values for the attributes, or null when it lacks one. */
    private static List<String> combination(VariationInput.Child item, List<String> attributes) {
        List<String> values = new ArrayList<>();
        for (String attribute : attributes) {
            Optional<String> value = item.attribute(attribute);
            if (value.isEmpty()) {
                return null;
            }
            values.add(value.get());
        }
        return values;
    }
}

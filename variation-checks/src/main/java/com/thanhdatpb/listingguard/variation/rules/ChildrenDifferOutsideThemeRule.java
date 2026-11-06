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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Children that look like different products put under one parent (ADR-015). Each title is lower-cased and cut into
 * tokens; the tokens that are words of the child's own theme attribute values (all of its attributes when the theme's
 * attributes are not known) and the ruleset's ignored size and color words are removed, keeping the order. The rest is the
 * child's remainder. If the remainders differ, the largest group of equal remainders (the first, on a tie) is the
 * reference and every child outside it gets a finding that quotes both remainders. A heuristic: it is a warning at most,
 * and the message says "may", not "is".
 */
final class ChildrenDifferOutsideThemeRule implements VariationRule {

    private final Set<String> ignoreWords;
    private final Findings findings;

    ChildrenDifferOutsideThemeRule(VariationRuleset ruleset) {
        this.ignoreWords = ruleset.childrenDiffer().ignoreWords();
        this.findings = new Findings(ruleset, RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME);
    }

    @Override
    public String id() {
        return RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME;
    }

    @Override
    public Set<String> marketplaces() {
        return findings.spec().marketplaces();
    }

    @Override
    public List<VariationFinding> apply(RuleContext context) {
        List<VariationInput.Child> children = context.input().children();
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        List<Integer> titled = new ArrayList<>();
        List<String> remainders = new ArrayList<>();
        for (int i = 0; i < children.size(); i++) {
            VariationInput.Child item = children.get(i);
            if (item.title() == null) {
                remainders.add(null);
                continue;
            }
            String remainder = remainder(item, context);
            remainders.add(remainder);
            titled.add(i);
            groups.computeIfAbsent(remainder, key -> new ArrayList<>()).add(i);
        }
        if (titled.size() < 2 || groups.size() < 2) {
            return List.of();
        }
        Map.Entry<String, List<Integer>> reference = null;
        for (Map.Entry<String, List<Integer>> group : groups.entrySet()) {
            if (reference == null || group.getValue().size() > reference.getValue().size()) {
                reference = group;
            }
        }
        int referenceIndex = reference.getValue().get(0);
        List<VariationFinding> out = new ArrayList<>();
        for (int i : titled) {
            if (reference.getValue().contains(i)) {
                continue;
            }
            out.add(findings.make(RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME, child(i, "title"), remainders.get(i),
                    List.of(child(referenceIndex, "title")),
                    values("position", position(i), "remainder", remainders.get(i), "reference_position",
                            position(referenceIndex), "reference_remainder", reference.getKey())));
        }
        return out;
    }

    private String remainder(VariationInput.Child item, RuleContext context) {
        Set<String> removed = new HashSet<>(ignoreWords);
        if (context.themeAttributesKnown()) {
            for (String attribute : context.themeAttributes()) {
                Optional<String> value = item.attribute(attribute);
                value.ifPresent(text -> removed.addAll(Texts.tokens(text)));
            }
        } else {
            item.attributes().values().forEach(text -> removed.addAll(Texts.tokens(text)));
        }
        return String.join(" ", Texts.tokens(item.title()).stream().filter(token -> !removed.contains(token)).toList());
    }
}

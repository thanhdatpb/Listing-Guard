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
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Amazon: there is a parent with a SKU and a theme, every child points at the parent's SKU, and every child fills the
 * attributes of the theme (ADR-015). When the theme is not one the ruleset defines, the attributes are not checked and an
 * INFO says so.
 */
final class ParentChildStructureRule implements VariationRule {

    private final Findings findings;

    ParentChildStructureRule(VariationRuleset ruleset) {
        this.findings = new Findings(ruleset, RuleIds.PARENT_CHILD_STRUCTURE);
    }

    @Override
    public String id() {
        return RuleIds.PARENT_CHILD_STRUCTURE;
    }

    @Override
    public Set<String> marketplaces() {
        return findings.spec().marketplaces();
    }

    @Override
    public List<VariationFinding> apply(RuleContext context) {
        VariationInput input = context.input();
        VariationInput.Parent parent = input.parent();
        List<VariationFinding> out = new ArrayList<>();

        String parentSku = parent == null || parent.sku() == null ? null : Texts.canonical(parent.sku());
        if (parent == null) {
            out.add(findings.make(RuleIds.PARENT_MISSING, "parent", null, List.of(), values()));
        } else if (parentSku == null) {
            out.add(findings.make(RuleIds.PARENT_MISSING, "parent.sku", null, List.of(), values()));
        }
        String themeName = parent == null ? null : parent.variationTheme();
        if (parent != null && themeName == null) {
            out.add(findings.make(RuleIds.PARENT_THEME_MISSING, "parent.variation_theme", null, List.of(), values()));
        }
        if (input.children().isEmpty()) {
            out.add(findings.make(RuleIds.NO_CHILDREN, "children", null, List.of(), values()));
            return out;
        }
        Optional<VariationRuleset.Theme> theme = context.theme();
        if (themeName != null && theme.isEmpty()) {
            out.add(findings.make(RuleIds.THEME_ATTRIBUTES_UNKNOWN, "parent.variation_theme", themeName, List.of(),
                    values("theme", themeName)));
        }

        for (int i = 0; i < input.children().size(); i++) {
            VariationInput.Child item = input.children().get(i);
            if (parentSku != null) {
                if (item.parentSku() == null) {
                    out.add(findings.make(RuleIds.CHILD_PARENT_SKU_MISSING, child(i, "parent_sku"), null, List.of(),
                            values("position", position(i), "parent_sku", parentSku)));
                } else if (!Texts.canonical(item.parentSku()).equals(parentSku)) {
                    out.add(findings.make(RuleIds.CHILD_PARENT_SKU_MISMATCH, child(i, "parent_sku"), item.parentSku(),
                            List.of("parent.sku"),
                            values("position", position(i), "parent_sku", parentSku, "actual", item.parentSku())));
                }
            }
            if (theme.isPresent()) {
                for (String attribute : theme.get().attributes()) {
                    if (item.attribute(attribute).isEmpty()) {
                        out.add(findings.make(RuleIds.CHILD_ATTRIBUTE_MISSING, child(i, "attributes." + attribute), null,
                                List.of(), values("position", position(i), "theme", theme.get().name(),
                                        "attribute", attribute)));
                    }
                }
            }
        }
        return out;
    }
}

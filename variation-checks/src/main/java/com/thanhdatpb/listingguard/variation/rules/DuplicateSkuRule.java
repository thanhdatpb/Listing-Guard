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
import java.util.Set;

/**
 * A child SKU that an earlier child already uses, or that equals the parent's SKU (ADR-015). SKUs are compared exactly
 * after Unicode NFC, so case matters. One finding for each later occurrence, naming the first. A child without a SKU is
 * skipped.
 */
final class DuplicateSkuRule implements VariationRule {

    private final Findings findings;

    DuplicateSkuRule(VariationRuleset ruleset) {
        this.findings = new Findings(ruleset, RuleIds.DUPLICATE_SKU);
    }

    @Override
    public String id() {
        return RuleIds.DUPLICATE_SKU;
    }

    @Override
    public Set<String> marketplaces() {
        return findings.spec().marketplaces();
    }

    @Override
    public List<VariationFinding> apply(RuleContext context) {
        VariationInput.Parent parent = context.input().parent();
        String parentSku = parent == null || parent.sku() == null ? null : Texts.canonical(parent.sku());
        List<VariationFinding> out = new ArrayList<>();
        Map<String, Integer> firstSeen = new HashMap<>();
        List<VariationInput.Child> children = context.input().children();
        for (int i = 0; i < children.size(); i++) {
            String sku = children.get(i).sku();
            if (sku == null) {
                continue;
            }
            String canonical = Texts.canonical(sku);
            Integer first = firstSeen.get(canonical);
            if (parentSku != null && canonical.equals(parentSku)) {
                out.add(findings.make(RuleIds.SKU_EQUALS_PARENT, child(i, "sku"), sku, List.of("parent.sku"),
                        values("position", position(i), "sku", sku)));
            } else if (first != null) {
                out.add(findings.make(RuleIds.DUPLICATE_SKU_KIND, child(i, "sku"), sku, List.of(child(first, "sku")),
                        values("position", position(i), "first_position", position(first), "sku", sku)));
            }
            firstSeen.putIfAbsent(canonical, i);
        }
        return out;
    }
}

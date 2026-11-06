package com.thanhdatpb.listingguard.variation.rules;

import static com.thanhdatpb.listingguard.variation.rules.Findings.child;
import static com.thanhdatpb.listingguard.variation.rules.Findings.position;
import static com.thanhdatpb.listingguard.variation.rules.Findings.values;

import com.thanhdatpb.listingguard.variation.Texts;
import com.thanhdatpb.listingguard.variation.VariationFinding;
import com.thanhdatpb.listingguard.variation.VariationInput;
import com.thanhdatpb.listingguard.variation.config.RuleIds;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Amazon apparel (ADR-015), only for a parent whose product type the ruleset lists as apparel: every child fills the
 * required size attributes, those with a closed list hold one of its values, and a size class that is a range comes with
 * a {@code size_to} larger than {@code size}. Numbers are compared as numbers and alpha sizes by their place in the
 * ruleset's scale; a range that cannot be compared is an INFO, never a warning.
 */
final class ApparelSizeAttributesRule implements VariationRule {

    private final VariationRuleset.Apparel apparel;
    private final Findings findings;

    ApparelSizeAttributesRule(VariationRuleset ruleset) {
        this.apparel = ruleset.apparel();
        this.findings = new Findings(ruleset, RuleIds.APPAREL_SIZE_ATTRIBUTES);
    }

    @Override
    public String id() {
        return RuleIds.APPAREL_SIZE_ATTRIBUTES;
    }

    @Override
    public Set<String> marketplaces() {
        return findings.spec().marketplaces();
    }

    @Override
    public List<VariationFinding> apply(RuleContext context) {
        VariationInput.Parent parent = context.input().parent();
        if (parent == null || !apparel.covers(parent.productType())) {
            return List.of();
        }
        List<VariationFinding> out = new ArrayList<>();
        List<VariationInput.Child> children = context.input().children();
        for (int i = 0; i < children.size(); i++) {
            VariationInput.Child item = children.get(i);
            for (String attribute : apparel.requiredAttributes()) {
                Optional<String> value = item.attribute(attribute);
                if (value.isEmpty()) {
                    out.add(findings.make(RuleIds.SIZE_ATTRIBUTE_MISSING, child(i, "attributes." + attribute), null,
                            List.of(), values("position", position(i), "attribute", attribute,
                                    "product_type", parent.productType())));
                    continue;
                }
                List<String> allowed = apparel.valueLists().get(attribute);
                if (allowed != null && allowed.stream().noneMatch(entry -> Texts.fold(entry).equals(Texts.fold(value.get())))) {
                    out.add(findings.make(RuleIds.SIZE_ATTRIBUTE_INVALID, child(i, "attributes." + attribute), value.get(),
                            List.of(), values("position", position(i), "attribute", attribute, "value", value.get(),
                                    "allowed", String.join(", ", allowed))));
                }
            }
            range(i, item, out);
        }
        return out;
    }

    private void range(int index, VariationInput.Child item, List<VariationFinding> out) {
        VariationRuleset.Range range = apparel.range();
        Optional<String> sizeClass = item.attribute(range.classAttribute());
        if (sizeClass.isEmpty()) {
            return;
        }
        VariationRuleset.RangeKind kind = range.classes().get(Texts.fold(sizeClass.get()));
        if (kind == null) {
            return;
        }
        String sizeToField = child(index, "attributes." + range.sizeToAttribute());
        Optional<String> sizeTo = item.attribute(range.sizeToAttribute());
        if (sizeTo.isEmpty()) {
            out.add(findings.make(RuleIds.SIZE_TO_MISSING, sizeToField, null, List.of(), values("position", position(index),
                    "attribute", range.sizeToAttribute(), "size_class", sizeClass.get())));
            return;
        }
        Optional<String> size = item.attribute(range.sizeAttribute());
        if (size.isEmpty() || kind == VariationRuleset.RangeKind.NONE) {
            return;
        }
        List<String> related = List.of(child(index, "attributes." + range.sizeAttribute()));
        Integer order = compare(kind, size.get(), sizeTo.get());
        if (order == null) {
            out.add(findings.make(RuleIds.SIZE_RANGE_NOT_COMPARABLE, sizeToField, sizeTo.get(), related,
                    values("position", position(index), "size", size.get(), "size_to", sizeTo.get(),
                            "size_class", sizeClass.get())));
        } else if (order >= 0) {
            out.add(findings.make(RuleIds.SIZE_TO_NOT_GREATER, sizeToField, sizeTo.get(), related,
                    values("position", position(index), "attribute", range.sizeToAttribute(), "size", size.get(),
                            "size_to", sizeTo.get())));
        }
    }

    /** Positive when {@code size} is larger than {@code sizeTo}, 0 when equal; null when they cannot be compared. */
    private Integer compare(VariationRuleset.RangeKind kind, String size, String sizeTo) {
        if (kind == VariationRuleset.RangeKind.NUMERIC) {
            BigDecimal from = number(size);
            BigDecimal to = number(sizeTo);
            return from == null || to == null ? null : from.compareTo(to);
        }
        int from = apparel.alphaScale().rank(size);
        int to = apparel.alphaScale().rank(sizeTo);
        return from < 0 || to < 0 ? null : Integer.compare(from, to);
    }

    private static BigDecimal number(String text) {
        try {
            return new BigDecimal(Texts.canonical(text));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

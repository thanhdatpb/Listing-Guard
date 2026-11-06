package com.thanhdatpb.listingguard.variation.rules;

import static com.thanhdatpb.listingguard.variation.rules.Findings.values;

import com.thanhdatpb.listingguard.variation.Texts;
import com.thanhdatpb.listingguard.variation.VariationFinding;
import com.thanhdatpb.listingguard.variation.VariationInput;
import com.thanhdatpb.listingguard.variation.config.RuleIds;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Amazon: the parent's theme is one of the themes the ruleset lists for its product type (ADR-015). A product type the
 * ruleset does not list gets an INFO ("no data for this type"), never an error; a missing theme is reported by
 * {@code parent_child_structure}, not here.
 */
final class VariationThemeAllowedRule implements VariationRule {

    private final VariationRuleset ruleset;
    private final Findings findings;

    VariationThemeAllowedRule(VariationRuleset ruleset) {
        this.ruleset = ruleset;
        this.findings = new Findings(ruleset, RuleIds.VARIATION_THEME_ALLOWED);
    }

    @Override
    public String id() {
        return RuleIds.VARIATION_THEME_ALLOWED;
    }

    @Override
    public Set<String> marketplaces() {
        return findings.spec().marketplaces();
    }

    @Override
    public List<VariationFinding> apply(RuleContext context) {
        VariationInput.Parent parent = context.input().parent();
        if (parent == null) {
            return List.of();
        }
        String productType = parent.productType();
        if (productType == null) {
            return List.of(findings.make(RuleIds.PRODUCT_TYPE_MISSING, "parent.product_type", null, List.of(), values()));
        }
        Optional<VariationRuleset.ProductTypeThemes> entry = ruleset.themeAllowed().find(productType);
        if (entry.isEmpty()) {
            return List.of(findings.make(RuleIds.PRODUCT_TYPE_NOT_COVERED, "parent.product_type", productType, List.of(),
                    values("product_type", productType)));
        }
        String theme = parent.variationTheme();
        if (theme == null) {
            return List.of();
        }
        boolean allowed = entry.get().themes().stream().anyMatch(name -> Texts.fold(name).equals(Texts.fold(theme)));
        if (allowed) {
            return List.of();
        }
        return List.of(findings.make(RuleIds.THEME_NOT_ALLOWED, "parent.variation_theme", theme, List.of(),
                values("theme", theme, "product_type", entry.get().productType(), "allowed",
                        String.join(", ", entry.get().themes()))));
    }
}

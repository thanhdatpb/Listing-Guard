package com.thanhdatpb.listingguard.variation.rules;

import com.thanhdatpb.listingguard.variation.Texts;
import com.thanhdatpb.listingguard.variation.VariationInput;
import com.thanhdatpb.listingguard.variation.config.VariationRuleset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What every rule shares for one check: the input, the ruleset and the theme of the family. The theme decides which
 * attributes make a child's combination: for Amazon the attributes of the parent's theme when the ruleset knows the theme,
 * for Etsy the distinct names in {@code variation_properties}. When they are not known the list is empty and the rules that
 * need it say nothing (or fall back, as their documentation says).
 */
public final class RuleContext {

    private final VariationInput input;
    private final VariationRuleset ruleset;
    private final Optional<VariationRuleset.Theme> theme;
    private final List<String> themeAttributes;

    private RuleContext(VariationInput input, VariationRuleset ruleset, Optional<VariationRuleset.Theme> theme,
                        List<String> themeAttributes) {
        this.input = input;
        this.ruleset = ruleset;
        this.theme = theme;
        this.themeAttributes = themeAttributes;
    }

    public static RuleContext of(VariationInput input, VariationRuleset ruleset) {
        if (VariationRuleset.ETSY.equals(input.marketplace())) {
            return new RuleContext(input, ruleset, Optional.empty(), distinct(input.variationProperties()));
        }
        VariationInput.Parent parent = input.parent();
        Optional<VariationRuleset.Theme> theme = parent == null ? Optional.empty() : ruleset.theme(parent.variationTheme());
        return new RuleContext(input, ruleset, theme, theme.map(VariationRuleset.Theme::attributes).orElse(List.of()));
    }

    public VariationInput input() {
        return input;
    }

    public VariationRuleset ruleset() {
        return ruleset;
    }

    /** The parent's theme when the ruleset defines it (Amazon only). */
    public Optional<VariationRuleset.Theme> theme() {
        return theme;
    }

    /** The attribute names of the combination, or an empty list when they are not known. */
    public List<String> themeAttributes() {
        return themeAttributes;
    }

    public boolean themeAttributesKnown() {
        return !themeAttributes.isEmpty();
    }

    /** The names without a repeat ignoring case, in the order sent, spelled as first sent. */
    static List<String> distinct(List<String> names) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (seen.add(Texts.fold(name))) {
                result.add(name);
            }
        }
        return result;
    }
}

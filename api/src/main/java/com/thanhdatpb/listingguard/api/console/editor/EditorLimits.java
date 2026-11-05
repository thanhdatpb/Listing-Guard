package com.thanhdatpb.listingguard.api.console.editor;

import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;

/**
 * The limits the editor shows next to a field (counters, the preview). Every number is read from the active ruleset of the
 * marketplace, never written in Java (ADR-001). A limit is {@code null} when the ruleset has no such rule, the rule is
 * switched off, or it has no such setting: the editor then shows no counter for it.
 *
 * <p>Only the limits of the selected marketplace are read: Etsy has the tag limits, Amazon the bullet and search term limits.
 * For Amazon a product type override of the ruleset applies, as it does to the check itself.
 */
public record EditorLimits(
        Integer titleMax,
        Integer tagLengthMax,
        Integer tagCountMax,
        Integer bulletCountMax,
        Integer bulletLengthMax,
        Integer searchTermsMaxBytes) {

    public static EditorLimits of(RulesetConfig config, EditorMarketplace marketplace, String productType) {
        String category = marketplace.isAmazon() ? productType : null;
        Integer title = read(config, "title_length", "max_length", category);
        if (marketplace.isEtsy()) {
            return new EditorLimits(title, read(config, "tag_length", "max_length", null),
                    read(config, "tag_count", "max_count", null), null, null, null);
        }
        return new EditorLimits(title, null, null, read(config, "bullet_count", "max_count", category),
                read(config, "bullet_length", "max_length", category),
                read(config, "search_terms_length", "max_bytes", category));
    }

    private static Integer read(RulesetConfig config, String ruleId, String param, String category) {
        RuleSettings settings = config.rules().get(ruleId);
        if (settings == null) {
            return null;
        }
        RuleSettings applicable = settings.forCategory(category);
        if (!applicable.enabled()) {
            return null;
        }
        return applicable.params().get(param) instanceof Number number ? number.intValue() : null;
    }
}

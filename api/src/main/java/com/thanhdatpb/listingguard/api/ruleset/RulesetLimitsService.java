package com.thanhdatpb.listingguard.api.ruleset;

import com.thanhdatpb.listingguard.ruleengine.RuleEngine;
import com.thanhdatpb.listingguard.ruleengine.RuleLimit;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * The numeric limits of the active text ruleset of a marketplace. The one place that answers "what are the limits": the API
 * endpoint {@code GET /api/v1/rulesets/{marketplace}/limits} and the counters of the console editor both call it, so the two
 * cannot differ. It reads nothing but the loaded ruleset and the rules, stores nothing, and has no plan or quota.
 */
@Service
public class RulesetLimitsService {

    private final ActiveRulesets rulesets;
    private final RuleEngine engine;

    public RulesetLimitsService(ActiveRulesets rulesets, RuleEngine engine) {
        this.rulesets = rulesets;
        this.engine = engine;
    }

    /**
     * @param marketplace the marketplace code, ignoring case and surrounding blanks
     * @param productType the Amazon product type whose override applies, matched ignoring case; null or blank for the defaults
     *                    (a product type with no override also gets the defaults, and so does any product type on Etsy)
     * @throws RulesetNotFoundException when no ruleset is active for the marketplace
     */
    public Limits limits(String marketplace, String productType) {
        String code = marketplace == null ? "" : marketplace.trim().toUpperCase(Locale.ROOT);
        RulesetConfig config = rulesets.find(RulesetConfig.DEFAULT_PACK_ID, code)
                .orElseThrow(() -> new RulesetNotFoundException(marketplace, rulesets.marketplaces(RulesetConfig.DEFAULT_PACK_ID)))
                .config();
        String category = productType == null || productType.isBlank() ? null : productType.trim();
        return new Limits(config.marketplace(), config.version(), category, engine.limits(config, category));
    }

    /**
     * @param marketplace    the upper case code of the marketplace
     * @param rulesetVersion the version of the ruleset the limits come from
     * @param productType    the product type asked for, trimmed as sent (not upper-cased), or null when none was asked for
     * @param limits         every limit of the ruleset's rules, in rule order, switched-off rules included
     */
    public record Limits(String marketplace, String rulesetVersion, String productType, List<RuleLimit> limits) {
    }
}

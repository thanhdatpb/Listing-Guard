package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/**
 * A deterministic listing rule. Thresholds, word lists and the severity all come from the
 * {@link RulesetConfig} (ADR-001); a rule class holds logic only.
 */
public interface Rule {

    /** Stable identifier; also the key of this rule in the ruleset YAML. */
    String id();

    List<Finding> evaluate(Listing listing, RulesetConfig config);

    /** Severity configured for this rule in the given ruleset. */
    default Severity severity(RulesetConfig config) {
        return config.rule(id()).severity();
    }

    /**
     * Reads every config key this rule needs so a missing or malformed key fails at startup, not on
     * the first request. Override and call the typed getters.
     */
    default void validate(RulesetConfig config) {
        config.rule(id());
    }

    /** A finding with no single offending value (for example a missing field). */
    default Finding finding(RulesetConfig config, String field, String message, String suggestion) {
        return finding(config, field, null, message, suggestion);
    }

    /** @param value the exact value that caused the finding, so clients can match by value, not position */
    default Finding finding(RulesetConfig config, String field, String value, String message, String suggestion) {
        return finding(config, config.rule(id()), field, value, message, suggestion);
    }

    /** A finding rated by the given settings, for rules whose settings depend on the listing's category. */
    default Finding finding(RulesetConfig config, RuleSettings settings, String field, String value, String message,
                            String suggestion) {
        return new Finding(id(), settings.severity(), field, value, message, suggestion, config.version());
    }
}

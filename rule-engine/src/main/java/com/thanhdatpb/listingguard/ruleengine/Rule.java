package com.thanhdatpb.listingguard.ruleengine;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.FixSuggestion;
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
     * The numbers this rule enforces, read from {@code settings}: the settings that apply to one category (the defaults, or
     * the override of an Amazon product type), already merged. A rule with a numeric limit overrides this and declares each
     * one with the unit that its {@link #evaluate} really counts; a rule with no number (characters, banned words, required
     * fields) keeps the default and declares none. Clients read the result through {@link RuleEngine#limits}, so a new rule
     * shows its limits without any change outside the rule.
     *
     * <p>Call it only for a ruleset that passed {@link #validate}: it reads the same keys and does not check them again.
     */
    default List<Limit> limits(RuleSettings settings) {
        return List.of();
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
        return finding(config, settings, field, value, message, suggestion, null);
    }

    /**
     * A finding with a candidate fix (ADR-014); {@code fix} is null when no fix is certain. The fix is only a
     * candidate until {@link RuleEngine} verifies it against the whole ruleset.
     */
    default Finding finding(RulesetConfig config, String field, String value, String message, String suggestion,
                            FixSuggestion fix) {
        return finding(config, config.rule(id()), field, value, message, suggestion, fix);
    }

    /** As above, rated by the given settings. */
    default Finding finding(RulesetConfig config, RuleSettings settings, String field, String value, String message,
                            String suggestion, FixSuggestion fix) {
        return new Finding(id(), settings.severity(), field, value, message, suggestion, config.version(), fix);
    }
}

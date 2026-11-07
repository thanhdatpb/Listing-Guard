package com.thanhdatpb.listingguard.personalization.rules;

import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import com.thanhdatpb.listingguard.personalization.config.RuleIds;
import java.util.List;

/** The character rules of a ruleset, in the order the ruleset declares them (ADR-016). */
public final class TextRules {

    private TextRules() {
    }

    public static List<TextRule> create(PersonalizationRuleset ruleset) {
        return ruleset.rules().stream().map(spec -> (TextRule) switch (spec.id()) {
            case RuleIds.UNSUPPORTED_CHARACTERS -> new UnsupportedCharactersRule(spec);
            case RuleIds.TOO_LONG -> new TooLongRule(spec);
            case RuleIds.WHITESPACE_ISSUE -> new WhitespaceIssueRule(spec);
            default -> throw new IllegalStateException("No implementation for rule " + spec.id());
        }).toList();
    }
}

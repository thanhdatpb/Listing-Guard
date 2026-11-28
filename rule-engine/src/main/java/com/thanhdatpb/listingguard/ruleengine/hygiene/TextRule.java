package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.FixSuggestion;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Base of the content-hygiene rules of ADR-039: each reads a list of listing texts ({@code fields} in the ruleset) and reports what it finds
 * in each, with the span of the match so the workspace can mark it. All of them are {@link #optional}: a ruleset that does not configure one
 * does not run it. They are internal quality rules, not marketplace policy; their sources say so.
 */
abstract class TextRule implements Rule {

    /** A match: {@code value} is the matched text (or, with a fix, the text the fix replaces), {@code start}..{@code end} the span to mark. */
    record Hit(String value, int start, int end, String message, String suggestion, FixSuggestion fix) {

        Hit(String value, int start, int end, String message, String suggestion) {
            this(value, start, end, message, suggestion, null);
        }
    }

    @Override
    public final boolean optional() {
        return true;
    }

    @Override
    public final void validate(RulesetConfig config) {
        RuleSettings settings = config.rule(id());
        ListingTexts.validate(id(), settings.stringListParam("fields"));
        validateSettings(settings);
    }

    abstract void validateSettings(RuleSettings settings);

    /** The matches in one text, in order of position. */
    abstract List<Hit> scan(String field, String text, RuleSettings settings);

    @Override
    public final List<Finding> evaluate(Listing listing, RulesetConfig config) {
        RuleSettings settings = config.rule(id());
        List<Finding> findings = new ArrayList<>();
        for (FieldText text : ListingTexts.of(listing, settings.stringListParam("fields"))) {
            for (Hit hit : scan(text.field(), text.text(), settings)) {
                Finding finding = finding(config, text.field(), hit.value(), hit.message(), hit.suggestion(), hit.fix());
                findings.add(finding.withSpan(hit.start(), hit.end()));
            }
        }
        return findings;
    }

    /** Quotes a value for a message, cutting a long one. */
    static String quote(String text) {
        int length = text.codePointCount(0, text.length());
        if (length <= 30) {
            return "'" + text + "'";
        }
        return "'" + text.substring(0, text.offsetByCodePoints(0, 30)) + "...'";
    }
}

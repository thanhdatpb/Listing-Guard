package com.thanhdatpb.listingguard.variation;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.variation.config.SourceStatus;
import java.util.List;

/**
 * One thing a rule found (ADR-015). {@code kind} is the case inside the rule and, like {@code ruleId}, is stable.
 * {@code sourceStatus} is how well the rule is backed by the marketplace's documentation. {@code field} is a path such as
 * {@code parent.sku}, {@code children[3].attributes.size} or {@code variation_properties[2]} (indexes are zero-based and
 * count the cleaned lists). {@code value} is the offending text, cut to the ruleset's limit, or null when nothing applies;
 * {@code relatedFields} are the other places the message refers to. {@code message} and {@code suggestion} are Vietnamese,
 * cite the real values and numbers, and propose no replacement value.
 */
public record VariationFinding(String ruleId, String kind, Severity severity, SourceStatus sourceStatus, String field,
                               String value, List<String> relatedFields, String message, String suggestion) {

    public VariationFinding {
        relatedFields = List.copyOf(relatedFields);
    }
}

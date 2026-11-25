package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.FieldScopedRule;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Every configured field must be present. Which fields are required is decided by the ruleset. A caller whose data never had a field
 * can leave it out of the check ({@link FieldScopedRule}, ADR-052); a field that was supplied and is empty is still reported.
 */
public final class RequiredFieldsRule implements FieldScopedRule {

    public static final String ID = "required_fields";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        for (String field : config.rule(ID).stringListParam("fields")) {
            presenceCheck(field);
        }
    }

    @Override
    public List<String> scopedFields(Listing listing, RulesetConfig config) {
        return config.rule(ID).stringListParam("fields");
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        return evaluate(listing, config, Set.of());
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config, Set<String> skipped) {
        List<Finding> findings = new ArrayList<>();
        for (String field : config.rule(ID).stringListParam("fields")) {
            if (!skipped.contains(field) && !presenceCheck(field).test(listing)) {
                findings.add(finding(config, field,
                        "Thiếu trường bắt buộc '" + field + "'.",
                        "Bổ sung giá trị cho trường '" + field + "' trước khi publish."));
            }
        }
        return findings;
    }

    private static Predicate<Listing> presenceCheck(String field) {
        return switch (field) {
            case "title" -> l -> l.title() != null;
            case "description" -> l -> l.description() != null;
            case "price" -> l -> l.price() != null;
            case "quantity" -> l -> l.quantity() != null;
            case "who_made" -> l -> l.whoMade() != null;
            case "when_made" -> l -> l.whenMade() != null;
            case "category" -> l -> l.category() != null;
            case "tags" -> l -> !l.tags().isEmpty();
            case "materials" -> l -> !l.materials().isEmpty();
            case "images" -> l -> !l.images().isEmpty();
            default -> throw new RulesetException("Rule '" + ID + "': unknown field '" + field + "'");
        };
    }
}

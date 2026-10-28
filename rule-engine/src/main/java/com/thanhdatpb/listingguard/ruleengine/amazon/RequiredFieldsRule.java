package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Every configured field must be present. Which fields are required is decided by the ruleset, and a product
 * type can override the list (Amazon's required attributes depend on the product type).
 */
public final class RequiredFieldsRule extends AmazonRule {

    public static final String ID = "required_fields";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        for (String field : settings.stringListParam("fields")) {
            presenceCheck(field);
        }
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        List<Finding> findings = new ArrayList<>();
        for (String field : settings.stringListParam("fields")) {
            if (!presenceCheck(field).test(listing)) {
                findings.add(finding(config, settings, field, null,
                        "Thiếu trường bắt buộc '" + field + "'.",
                        "Bổ sung giá trị cho trường '" + field + "' trước khi publish."));
            }
        }
        return findings;
    }

    private static Predicate<Listing> presenceCheck(String field) {
        return switch (field) {
            case "title" -> l -> l.title() != null;
            case "brand" -> l -> l.brand() != null;
            case "description" -> l -> l.description() != null;
            case "bullet_points" -> l -> !l.bulletPoints().isEmpty();
            case "search_terms" -> l -> l.searchTerms() != null;
            case "product_type" -> l -> l.productType() != null;
            case "price" -> l -> l.price() != null;
            case "quantity" -> l -> l.quantity() != null;
            case "images" -> l -> !l.images().isEmpty();
            default -> throw new RulesetException("Rule '" + ID + "': unknown field '" + field + "'");
        };
    }
}

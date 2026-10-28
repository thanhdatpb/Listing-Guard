package com.thanhdatpb.listingguard.ruleengine.amazon;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.util.List;

/**
 * Tells the caller that the listing's product type has no overrides in the ruleset, so only the default
 * (category-wide) settings were applied. It is about the ruleset's coverage, not about a problem in the listing.
 * A listing without a product type is RequiredFields' job.
 */
public final class CategoryNotCoveredRule extends AmazonRule {

    public static final String ID = "category_not_covered";

    @Override
    public String id() {
        return ID;
    }

    @Override
    void validateSettings(RuleSettings settings) {
        // no parameters
    }

    @Override
    List<Finding> check(Listing listing, RulesetConfig config, RuleSettings settings) {
        String productType = listing.productType();
        if (productType == null || config.coversCategory(productType)) {
            return List.of();
        }
        return List.of(finding(config, settings, "product_type", productType,
                "Chưa có luật riêng cho product type '" + productType + "'; đang áp dụng luật chung.",
                "Không cần sửa listing. Luật chung vẫn được kiểm tra; giới hạn riêng của product type này sẽ được thêm vào ruleset khi có nguồn."));
    }
}

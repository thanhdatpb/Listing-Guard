package com.thanhdatpb.listingguard.ruleengine.etsy;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Price and quantity, when present, must be strictly greater than the configured minimums.
 * A missing value is RequiredFields' job.
 */
public final class PriceQuantityPositiveRule implements Rule {

    public static final String ID = "price_quantity_positive";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void validate(RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        settings.decimalParam("price_min_exclusive");
        settings.decimalParam("quantity_min_exclusive");
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        List<Finding> findings = new ArrayList<>();

        BigDecimal minPrice = settings.decimalParam("price_min_exclusive");
        if (listing.price() != null && listing.price().amount().compareTo(minPrice) <= 0) {
            findings.add(finding(config, "price",
                    "Giá là " + listing.price().amount().toPlainString() + ", phải lớn hơn " + minPrice.toPlainString() + ".",
                    "Nhập giá lớn hơn " + minPrice.toPlainString() + "."));
        }

        BigDecimal minQuantity = settings.decimalParam("quantity_min_exclusive");
        if (listing.quantity() != null && BigDecimal.valueOf(listing.quantity()).compareTo(minQuantity) <= 0) {
            findings.add(finding(config, "quantity",
                    "Số lượng là " + listing.quantity() + ", phải lớn hơn " + minQuantity.toPlainString() + ".",
                    "Nhập số lượng lớn hơn " + minQuantity.toPlainString() + "."));
        }
        return findings;
    }
}

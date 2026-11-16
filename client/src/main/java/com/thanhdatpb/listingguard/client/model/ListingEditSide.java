package com.thanhdatpb.listingguard.client.model;

/** The existing result for each applicable pack in one side of an edit comparison. */
public record ListingEditSide(CheckResult listingRules, TrademarkCheckResult trademarkRisk,
                              VariationCheckResult variationChecks) {
}

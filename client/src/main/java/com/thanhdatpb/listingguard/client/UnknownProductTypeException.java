package com.thanhdatpb.listingguard.client;

import java.util.List;

/**
 * {@code 400 unknown-product-type}: the trademark check named a {@code product_type} the active trademark ruleset does not
 * declare. Keys are exact ({@code Apparel} is not {@code apparel}). Nothing was stored or counted. Use one of
 * {@link #known()}, or leave the product type out.
 */
public class UnknownProductTypeException extends ListingGuardApiException {

    private static final long serialVersionUID = 1L;

    public UnknownProductTypeException(ApiProblem problem) {
        super(problem);
    }

    /** The product types the ruleset declares, as the API listed them; empty when it listed none. */
    public List<String> known() {
        return problem().texts("known");
    }
}

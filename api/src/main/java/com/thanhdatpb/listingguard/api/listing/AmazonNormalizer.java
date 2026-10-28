package com.thanhdatpb.listingguard.api.listing;

import static com.thanhdatpb.listingguard.api.listing.JsonFields.integer;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.price;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.strings;
import static com.thanhdatpb.listingguard.api.listing.JsonFields.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.core.listing.Listing;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Normalizes the simple Amazon listing format: {@code title}, {@code brand}, {@code bullet_points} (array),
 * {@code description}, {@code search_terms} (string, or array joined by a single space), {@code product_type},
 * {@code price}, {@code quantity} and {@code images} (array of URLs). Only maps and cleans values; it never enforces
 * marketplace limits. The product type is kept as written: matching it to the ruleset ignores case.
 */
@Component
public class AmazonNormalizer implements Normalizer {

    public static final String MARKETPLACE = "AMAZON";

    @Override
    public String marketplace() {
        return MARKETPLACE;
    }

    @Override
    public Listing normalize(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("Amazon listing must be a JSON object");
        }
        return new Listing(
                text(raw.get("title")),
                text(raw.get("description")),
                List.of(),
                price(raw.get("price")),
                null,
                List.of(),
                strings(raw.get("images")),
                integer(raw.get("quantity")),
                null,
                null,
                text(raw.get("brand")),
                strings(raw.get("bullet_points")),
                searchTerms(raw.get("search_terms")),
                text(raw.get("product_type")));
    }

    private static String searchTerms(JsonNode node) {
        if (node != null && node.isArray()) {
            List<String> terms = strings(node);
            return terms.isEmpty() ? null : String.join(" ", terms);
        }
        return text(node);
    }
}

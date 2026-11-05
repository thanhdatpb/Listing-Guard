package com.thanhdatpb.listingguard.api.console.editor;

/**
 * What the seller typed into the editor, as text. {@code tags}, {@code bulletPoints} hold one entry per line. Nothing here
 * is stored: the form travels in the request and back in the response, and the listing is never saved (ADR-014).
 * A missing parameter is the empty string, so a template never meets {@code null}.
 */
public record EditorForm(
        String marketplace,
        String title,
        String tags,
        String description,
        String bulletPoints,
        String price,
        String quantity,
        String category,
        String whoMade,
        String whenMade,
        String brand,
        String searchTerms,
        String productType) {

    public EditorForm {
        marketplace = marketplace == null ? "" : marketplace;
        title = title == null ? "" : title;
        tags = tags == null ? "" : tags;
        description = description == null ? "" : description;
        bulletPoints = bulletPoints == null ? "" : bulletPoints;
        price = price == null ? "" : price;
        quantity = quantity == null ? "" : quantity;
        category = category == null ? "" : category;
        whoMade = whoMade == null ? "" : whoMade;
        whenMade = whenMade == null ? "" : whenMade;
        brand = brand == null ? "" : brand;
        searchTerms = searchTerms == null ? "" : searchTerms;
        productType = productType == null ? "" : productType;
    }

    /** An empty form for the marketplace. */
    public static EditorForm empty(EditorMarketplace marketplace) {
        return new EditorForm(marketplace.code(), "", "", "", "", "", "", "", "", "", "", "", "");
    }

    /** True when any field the "Other listing fields" section holds has text. */
    public boolean hasOtherFields() {
        return !(price.isBlank() && quantity.isBlank() && category.isBlank() && whoMade.isBlank()
                && whenMade.isBlank() && brand.isBlank() && searchTerms.isBlank() && productType.isBlank());
    }

    public EditorForm withTitle(String value) {
        return new EditorForm(marketplace, value, tags, description, bulletPoints, price, quantity, category, whoMade,
                whenMade, brand, searchTerms, productType);
    }

    public EditorForm withTags(String value) {
        return new EditorForm(marketplace, title, value, description, bulletPoints, price, quantity, category, whoMade,
                whenMade, brand, searchTerms, productType);
    }
}

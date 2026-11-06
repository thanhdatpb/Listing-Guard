package com.thanhdatpb.listingguard.core.listing;

import java.util.List;

/**
 * Marketplace-neutral listing. Scalar fields are null when absent in the source; list fields are
 * never null. Values are normalized (trimmed) but never truncated or corrected: checking limits is
 * the job of the rule engine, not of the schema.
 *
 * <p>The first ten components are the Etsy view of a listing. {@code brand}, {@code bulletPoints},
 * {@code searchTerms}, {@code productType} and {@code itemHighlights} are used by Amazon; {@code productType} (for
 * example {@code LUGGAGE}) is what selects category overrides in an Amazon ruleset. {@code searchTerms} is one string;
 * a source that sends several terms has them joined by a single space. {@code itemHighlights} is one string, the
 * Amazon "Item Highlights" field.
 *
 * <p>{@code personalizationQuestions} are the structured personalization questions of an Etsy listing (empty when the seller
 * sent none). It is never null.
 *
 * <p>{@code customizationSurfaces} are the print surfaces of an Amazon Custom product with their inputs (empty when the seller
 * sent none). It is never null.
 */
public record Listing(
        String title,
        String description,
        List<String> tags,
        Money price,
        String category,
        List<String> materials,
        List<String> images,
        Integer quantity,
        String whoMade,
        String whenMade,
        String brand,
        List<String> bulletPoints,
        String searchTerms,
        String productType,
        String itemHighlights,
        List<PersonalizationQuestion> personalizationQuestions,
        List<CustomizationSurface> customizationSurfaces) {

    public Listing {
        tags = tags == null ? List.of() : List.copyOf(tags);
        materials = materials == null ? List.of() : List.copyOf(materials);
        images = images == null ? List.of() : List.copyOf(images);
        bulletPoints = bulletPoints == null ? List.of() : List.copyOf(bulletPoints);
        personalizationQuestions = personalizationQuestions == null ? List.of() : List.copyOf(personalizationQuestions);
        customizationSurfaces = customizationSurfaces == null ? List.of() : List.copyOf(customizationSurfaces);
    }

    /** A listing without customization surfaces; every component but {@code customizationSurfaces}. */
    public Listing(String title, String description, List<String> tags, Money price, String category,
                   List<String> materials, List<String> images, Integer quantity, String whoMade, String whenMade,
                   String brand, List<String> bulletPoints, String searchTerms, String productType,
                   String itemHighlights, List<PersonalizationQuestion> personalizationQuestions) {
        this(title, description, tags, price, category, materials, images, quantity, whoMade, whenMade,
                brand, bulletPoints, searchTerms, productType, itemHighlights, personalizationQuestions, List.of());
    }

    /** A listing without personalization questions or customization surfaces. */
    public Listing(String title, String description, List<String> tags, Money price, String category,
                   List<String> materials, List<String> images, Integer quantity, String whoMade, String whenMade,
                   String brand, List<String> bulletPoints, String searchTerms, String productType,
                   String itemHighlights) {
        this(title, description, tags, price, category, materials, images, quantity, whoMade, whenMade,
                brand, bulletPoints, searchTerms, productType, itemHighlights, List.of(), List.of());
    }

    /** An Etsy-style listing: the Amazon-only components are absent. */
    public Listing(String title, String description, List<String> tags, Money price, String category,
                   List<String> materials, List<String> images, Integer quantity, String whoMade, String whenMade) {
        this(title, description, tags, price, category, materials, images, quantity, whoMade, whenMade,
                null, List.of(), null, null, null);
    }

    /** An Amazon listing without Item Highlights: {@code itemHighlights} is absent. */
    public Listing(String title, String description, List<String> tags, Money price, String category,
                   List<String> materials, List<String> images, Integer quantity, String whoMade, String whenMade,
                   String brand, List<String> bulletPoints, String searchTerms, String productType) {
        this(title, description, tags, price, category, materials, images, quantity, whoMade, whenMade,
                brand, bulletPoints, searchTerms, productType, null);
    }

    /** A copy with another title; every other component is kept (used to check a proposed fix, ADR-014). */
    public Listing withTitle(String newTitle) {
        return new Listing(newTitle, description, tags, price, category, materials, images, quantity, whoMade, whenMade,
                brand, bulletPoints, searchTerms, productType, itemHighlights, personalizationQuestions,
                customizationSurfaces);
    }

    /** A copy with other tags; every other component is kept (used to check a proposed fix, ADR-014). */
    public Listing withTags(List<String> newTags) {
        return new Listing(title, description, newTags, price, category, materials, images, quantity, whoMade, whenMade,
                brand, bulletPoints, searchTerms, productType, itemHighlights, personalizationQuestions,
                customizationSurfaces);
    }
}

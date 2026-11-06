package com.thanhdatpb.listingguard.variation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A listing's variation family as the rules see it (docs/input-schema.md, "Variation checks"): the parent (Amazon), the
 * variation property names (Etsy) and the children. Already cleaned by the caller's normalizer, which only trims and drops
 * blanks; nothing here corrects a value. A text that is absent is {@code null}.
 *
 * @param marketplace         {@code AMAZON} or {@code ETSY}, upper case
 * @param parent              the parent, or null when none was sent (always null for Etsy)
 * @param variationProperties Etsy's variation types, in the order sent
 * @param children            the children, in the order sent
 */
public record VariationInput(String marketplace, Parent parent, List<String> variationProperties, List<Child> children) {

    public VariationInput {
        variationProperties = List.copyOf(variationProperties);
        children = List.copyOf(children);
    }

    /** The parent family member (Amazon). */
    public record Parent(String sku, String productType, String variationTheme, String title) {
    }

    /**
     * One child. {@code attributes} maps an attribute name to its value in the order sent; a blank value was dropped by
     * the normalizer.
     */
    public record Child(String sku, String parentSku, String title, Map<String, String> attributes) {

        public Child {
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }

        /**
         * The value of the attribute named {@code name}, compared ignoring case. If two names of this child differ only
         * by case, the one that sorts first by code point is read, so the answer does not depend on the order of the JSON.
         */
        public Optional<String> attribute(String name) {
            String wanted = name.toLowerCase(Locale.ROOT);
            Map<String, String> sorted = new TreeMap<>(attributes);
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                if (entry.getKey().toLowerCase(Locale.ROOT).equals(wanted)) {
                    return Optional.of(entry.getValue());
                }
            }
            return Optional.empty();
        }
    }
}

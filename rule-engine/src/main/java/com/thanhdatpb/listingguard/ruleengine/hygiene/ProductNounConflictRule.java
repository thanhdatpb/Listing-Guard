package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.ruleengine.Rule;
import com.thanhdatpb.listingguard.ruleengine.config.RuleSettings;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetConfig;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The title names a kind of product that the listing's product type does not (a title with {@code Mug} on a {@code SHIRT}). The nouns and the product
 * types they fit are tables of the ruleset; a product type the table does not list is not judged, and neither is a title that names no known
 * noun, or one that names the type's noun among others ({@code Mug and Tee Set}). The product type is read from {@code type_field}
 * ({@code product_type} for Amazon, {@code category} for Etsy). The finding is on the title at the noun. No fix.
 */
public final class ProductNounConflictRule implements Rule {

    public static final String ID = "product_noun_conflict";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean optional() {
        return true;
    }

    @Override
    public void validate(RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        typeField(settings);
        ProductNouns.from(settings);
    }

    private static String typeField(RuleSettings settings) {
        String field = settings.stringParam("type_field");
        if (!field.equals("product_type") && !field.equals("category")) {
            throw new RulesetException("Rule '" + ID + "': 'type_field' must be product_type or category, got " + field);
        }
        return field;
    }

    @Override
    public List<Finding> evaluate(Listing listing, RulesetConfig config) {
        RuleSettings settings = config.rule(ID);
        String type = typeField(settings).equals("product_type") ? listing.productType() : listing.category();
        if (listing.title() == null || type == null || type.isBlank()) {
            return List.of();
        }
        ProductNouns nouns = ProductNouns.from(settings);
        Optional<Set<String>> accepted = nouns.groupsOf(type);
        if (accepted.isEmpty()) {
            return List.of();
        }
        List<Mention> mentions = nouns.extract(listing.title());
        if (mentions.isEmpty() || mentions.stream().anyMatch(m -> accepted.get().contains(m.key()))) {
            return List.of();
        }
        Mention first = mentions.get(0);
        return List.of(finding(config, "title", first.text(),
                "Loại sản phẩm không khớp: title ghi " + TextRule.quote(first.text()) + " nhưng loại sản phẩm là "
                        + TextRule.quote(type.strip()) + " (hợp với: " + String.join(", ", accepted.get()) + ").",
                "Xem lại tiêu đề hoặc loại sản phẩm: bản nhân bản thường còn sót tên sản phẩm của listing cũ.")
                .withSpan(first.start(), first.end()));
    }
}

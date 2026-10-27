package com.thanhdatpb.listingguard.api.listing;

import com.thanhdatpb.listingguard.core.listing.Money;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Normalizes Etsy Open API v3 style listing payloads (snake_case fields). Only maps and cleans
 * values; it never enforces marketplace limits.
 */
@Component
public class EtsyNormalizer implements Normalizer {

    public static final String MARKETPLACE = "ETSY";

    @Override
    public String marketplace() {
        return MARKETPLACE;
    }

    @Override
    public Listing normalize(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("Etsy listing must be a JSON object");
        }
        return new Listing(
                text(raw.get("title")),
                text(raw.get("description")),
                strings(raw.get("tags"), true),
                price(raw.get("price")),
                category(raw),
                strings(raw.get("materials"), false),
                images(raw.get("images")),
                raw.hasNonNull("quantity") && raw.get("quantity").canConvertToInt() ? raw.get("quantity").intValue() : null,
                text(raw.get("who_made")),
                text(raw.get("when_made")));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static List<String> strings(JsonNode node, boolean dedupeIgnoreCase) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode item : node) {
            String value = text(item);
            if (value == null) {
                continue;
            }
            if (dedupeIgnoreCase && !seen.add(value.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.add(value);
        }
        return out;
    }

    private static List<String> images(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            String url = item.isObject() ? firstNonNull(text(item.get("url_fullxfull")), text(item.get("url"))) : text(item);
            if (url != null) {
                out.add(url);
            }
        }
        return out;
    }

    private static String category(JsonNode raw) {
        String category = text(raw.get("category"));
        if (category != null) {
            return category;
        }
        List<String> path = strings(raw.get("taxonomy_path"), false);
        return path.isEmpty() ? null : String.join(" > ", path);
    }

    private static Money price(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            JsonNode amount = node.get("amount");
            if (amount == null || !amount.isNumber() && !amount.isTextual()) {
                return null;
            }
            BigDecimal value = decimal(amount);
            if (value == null) {
                return null;
            }
            JsonNode divisor = node.get("divisor");
            if (divisor != null && divisor.isNumber() && divisor.intValue() != 0) {
                value = value.divide(BigDecimal.valueOf(divisor.intValue()), 6, RoundingMode.HALF_UP).stripTrailingZeros();
                if (value.scale() < 2) {
                    value = value.setScale(2);
                }
            }
            String currency = firstNonNull(text(node.get("currency_code")), text(node.get("currency")));
            return new Money(value, currency == null ? null : currency.toUpperCase(Locale.ROOT));
        }
        BigDecimal value = decimal(node);
        return value == null ? null : new Money(value, null);
    }

    private static BigDecimal decimal(JsonNode node) {
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}

package com.thanhdatpb.listingguard.api.listing;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.core.listing.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reading helpers shared by the normalizers. They only map and clean (trim, drop blanks); they never dedupe, truncate
 * or correct, because rules must see what the user wrote.
 */
final class JsonFields {

    private JsonFields() {
    }

    /** A trimmed string value, or null when absent, null, not a plain value or blank. */
    static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    /** Trims each entry and drops blanks. Duplicates are kept on purpose: the rule engine reports them. */
    static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            String value = text(item);
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    /** An integer value, or null when absent, null or not an integer. */
    static Integer integer(JsonNode node) {
        return node != null && node.canConvertToInt() && !node.isNull() ? node.intValue() : null;
    }

    /** A price as {@code {amount, divisor, currency_code}} or {@code {amount, currency}}, or a plain number. */
    static Money price(JsonNode node) {
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

    static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static BigDecimal decimal(JsonNode node) {
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

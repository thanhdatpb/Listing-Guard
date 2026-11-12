package com.thanhdatpb.listingguard.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
 * The request of {@code POST /api/v1/personalization-screenings} ({@code docs/input-schema.md}, "Personalization screening"):
 * the text a buyer typed for a custom product. The text is personal data: the SDK sends it exactly as typed (not trimmed, since
 * stray spaces are reported) and never logs it.
 *
 * @param marketplace  {@code ETSY} or {@code AMAZON}
 * @param productType  optional free text naming the product, for example {@code jersey}
 * @param printProfile optional code of a print profile the ruleset declares; {@code null} for the default. An undeclared one is
 *                     refused with {@code 400 unknown-print-profile}.
 * @param values       what the buyer typed, one entry per field of the order
 */
public record PersonalizationScreeningRequest(
        String marketplace,
        @JsonProperty("product_type") String productType,
        @JsonProperty("print_profile") String printProfile,
        List<PersonalizationValue> values) {

    public PersonalizationScreeningRequest {
        Objects.requireNonNull(marketplace, "marketplace");
        values = values == null ? null : List.copyOf(values);
    }

    /** A request with the default print profile and no product type. */
    public static PersonalizationScreeningRequest of(String marketplace, List<PersonalizationValue> values) {
        return new PersonalizationScreeningRequest(marketplace, null, null, values);
    }

    /** The same request with a print profile ({@code null} for the default). */
    public PersonalizationScreeningRequest withPrintProfile(String printProfile) {
        return new PersonalizationScreeningRequest(marketplace, productType, printProfile, values);
    }

    /** The same request with a product type ({@code null} for none). */
    public PersonalizationScreeningRequest withProductType(String productType) {
        return new PersonalizationScreeningRequest(marketplace, productType, printProfile, values);
    }

    /** Never shows the buyer's text: it is personal data. */
    @Override
    public String toString() {
        return "PersonalizationScreeningRequest[marketplace=" + marketplace + ", printProfile=" + printProfile + ", values="
                + (values == null ? 0 : values.size()) + "]";
    }
}

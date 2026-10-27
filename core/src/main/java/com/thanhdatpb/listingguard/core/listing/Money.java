package com.thanhdatpb.listingguard.core.listing;

import java.math.BigDecimal;
import java.util.Objects;

/** Monetary amount in major units (e.g. 19.99). {@code currency} is an ISO 4217 code or null if unknown. */
public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
    }
}

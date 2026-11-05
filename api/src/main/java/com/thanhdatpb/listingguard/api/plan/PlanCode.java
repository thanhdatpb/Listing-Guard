package com.thanhdatpb.listingguard.api.plan;

import java.util.Locale;
import java.util.Optional;

/** The plans a client can have (ADR-013). Held equal to the {@code ck_plan_code} constraint of V19 by a test. */
public enum PlanCode {
    FREE,
    SELLER,
    PRO;

    /** The plan with this code, ignoring case; empty for anything else. */
    public static Optional<PlanCode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

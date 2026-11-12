package com.thanhdatpb.listingguard.contract;

import java.util.Comparator;
import java.util.Objects;

/**
 * One change between the baseline and the current contract.
 *
 * @param rule     what kind of change it is, and so its class
 * @param location where it is, for example {@code GET /api/v1/checks/{id}} or {@code schema FindingView.fix}; together with the
 *                 rule it is what {@code docs/api/breaking-changes.yaml} names
 * @param detail   what changed, for a person (old and new value); not part of the identity of the change
 */
public record Change(Rule rule, String location, String detail) {

    /** Breaking first, then additive, then the rest; inside a class by location, then rule. */
    public static final Comparator<Change> ORDER = Comparator.comparing((Change change) -> change.changeClass())
            .thenComparing(Change::location)
            .thenComparing(change -> change.rule().id());

    public Change {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(location, "location");
        detail = detail == null ? "" : detail;
    }

    public ChangeClass changeClass() {
        return rule.changeClass();
    }

    /** The identity of the change: its rule and its location. */
    public String key() {
        return rule.id() + " @ " + location;
    }

    @Override
    public String toString() {
        return rule.id() + " at " + location + (detail.isEmpty() ? "" : " (" + detail + ")");
    }
}

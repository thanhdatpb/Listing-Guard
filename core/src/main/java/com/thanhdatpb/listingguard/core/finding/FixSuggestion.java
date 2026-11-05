package com.thanhdatpb.listingguard.core.finding;

import java.util.Objects;

/**
 * A concrete fix for the value of one finding (ADR-014): a Vietnamese {@code description} that cites the numbers and,
 * for {@link FixAction#REPLACE}, the {@code proposedValue}. It is advice only and is never applied by Listing Guard.
 * A fix never invents or substitutes words: it shortens a value at a word boundary or removes a duplicate.
 *
 * <p>{@code proposedValue} is non-blank for {@code REPLACE} and null for {@code REMOVE}; any other combination is
 * refused. The field it applies to is the field of the {@link Finding} that carries it.
 */
public record FixSuggestion(FixAction action, String description, String proposedValue) {

    public FixSuggestion {
        Objects.requireNonNull(action, "action");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("A fix suggestion needs a description");
        }
        switch (action) {
            case REPLACE -> {
                if (proposedValue == null || proposedValue.isBlank()) {
                    throw new IllegalArgumentException("A REPLACE fix needs a proposed value");
                }
            }
            case REMOVE -> {
                if (proposedValue != null) {
                    throw new IllegalArgumentException("A REMOVE fix has no proposed value");
                }
            }
        }
    }

    public static FixSuggestion replace(String description, String proposedValue) {
        return new FixSuggestion(FixAction.REPLACE, description, proposedValue);
    }

    public static FixSuggestion remove(String description) {
        return new FixSuggestion(FixAction.REMOVE, description, null);
    }
}

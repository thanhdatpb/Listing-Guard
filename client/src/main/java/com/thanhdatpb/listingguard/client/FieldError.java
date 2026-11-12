package com.thanhdatpb.listingguard.client;

/**
 * One entry of the {@code errors} member of an {@code invalid-request} problem.
 *
 * @param field   the field the API names, for example {@code listing}; may be {@code null}
 * @param message what is wrong, for example {@code must not be null}; may be {@code null}
 */
public record FieldError(String field, String message) {
}

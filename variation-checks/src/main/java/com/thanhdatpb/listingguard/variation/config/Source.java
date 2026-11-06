package com.thanhdatpb.listingguard.variation.config;

import java.util.List;

/**
 * Where a rule or a value comes from. {@code urls} is never empty for {@code SECONDARY} or {@code VERIFIED}: a value is
 * never marked above {@code UNVERIFIED} without a page to point at. {@code note} says what is ours or what could not be
 * read; it is null when there is nothing to add.
 */
public record Source(SourceStatus status, List<String> urls, String note) {

    public Source {
        urls = List.copyOf(urls);
    }
}

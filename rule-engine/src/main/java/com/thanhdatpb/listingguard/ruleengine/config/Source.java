package com.thanhdatpb.listingguard.ruleengine.config;

import java.util.List;

/** Documentation backing a rule's thresholds. {@code urls} may be empty only when unverified. */
public record Source(SourceStatus status, List<String> urls, String note) {

    public Source {
        urls = urls == null ? List.of() : List.copyOf(urls);
    }
}

package com.thanhdatpb.listingguard.rag.policy;

import java.time.LocalDate;
import java.util.List;

/** The policy documents to ingest, as listed in {@code policies/manifest.yaml}. */
public record PolicyManifest(List<Entry> documents) {

    public PolicyManifest {
        documents = List.copyOf(documents);
    }

    /**
     * @param file        file name inside {@code policies/raw/<marketplace in lower case>/}
     * @param marketplace marketplace code, upper case
     * @param title       document title, first element of every chunk's section path
     * @param url         canonical public URL
     * @param fetchedAt   date the file was saved
     * @param version     the page's own "Last updated" date, ISO format
     */
    public record Entry(String file, String marketplace, String title, String url, LocalDate fetchedAt, String version) {
    }
}

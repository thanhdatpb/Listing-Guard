package com.thanhdatpb.listingguard.client.model;

/**
 * A public page where a source record of a mark can be read (schema {@code SourceLinkView}).
 *
 * @param sourceId the source record's id
 * @param label    what the page is
 * @param url      the page's address
 */
public record SourceLink(String sourceId, String label, String url) {
}

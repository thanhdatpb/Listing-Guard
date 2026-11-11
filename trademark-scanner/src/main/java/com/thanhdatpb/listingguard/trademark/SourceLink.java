package com.thanhdatpb.listingguard.trademark;

/**
 * A public look-up page for the source record of a catalog mark (ADR-009, "Product classes, source links and generic
 * words"). The URL is built from a template in the ruleset, and only for a source the ruleset names; the scanner never
 * opens it.
 *
 * @param sourceId the stored source id the link was built from, such as {@code uspto:12345678}
 * @param label    what the page is, from the ruleset
 * @param url      the public address
 */
public record SourceLink(String sourceId, String label, String url) {
}

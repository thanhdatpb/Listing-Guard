/**
 * The Java client SDK of the Listing Guard REST API (ADR-023).
 *
 * <p>Start with {@link com.thanhdatpb.listingguard.client.ListingGuardClient#builder()}. Every method checks; none edits a
 * listing, applies a fix or publishes. Errors are {@link com.thanhdatpb.listingguard.client.ListingGuardException}s: a problem
 * answer of the API is a {@link com.thanhdatpb.listingguard.client.ListingGuardApiException} (or one of its typed subclasses),
 * no usable answer is a {@link com.thanhdatpb.listingguard.client.ListingGuardTransportException}. Request and response types
 * are in {@link com.thanhdatpb.listingguard.client.model}.
 */
package com.thanhdatpb.listingguard.client;

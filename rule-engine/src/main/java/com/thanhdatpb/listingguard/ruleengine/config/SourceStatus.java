package com.thanhdatpb.listingguard.ruleengine.config;

/** How well a threshold is backed by documentation. */
public enum SourceStatus {
    /** Confirmed against an official marketplace document. */
    VERIFIED,
    /** Consistent across secondary sources; the official page could not be read. */
    SECONDARY,
    /** No usable source found. */
    UNVERIFIED
}

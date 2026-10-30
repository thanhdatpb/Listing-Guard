package com.thanhdatpb.listingguard.trademark.catalog;

/**
 * The lifecycle status of a mark in a catalog source. Stored as a string with a CHECK constraint in
 * {@code trademark_entry} (keep the two in sync). The declaration order is the merge priority: when several source
 * records normalize to the same mark, the entry takes the status that is declared first.
 */
public enum MarkStatus {
    REGISTERED,
    PENDING,
    EXPIRED,
    CANCELLED
}

package com.thanhdatpb.listingguard.api.rewrite;

import java.util.List;

/**
 * One suggestion that passed the verification (ADR-037). It is text for the seller to read and copy; nothing applies it.
 *
 * @param number the position among the safe candidates, from 1, best first
 * @param title  the proposed title, or null when the candidate does not change it
 * @param tags   the tags it replaces, by their index in the cleaned tag list (blank lines left out, as the findings count them)
 */
public record RewriteCandidate(int number, String title, List<TagChange> tags) {

    public RewriteCandidate {
        tags = List.copyOf(tags);
    }

    /** One tag replaced: {@code from} is the exact current value, {@code to} the proposal. */
    public record TagChange(int index, String from, String to) {
    }
}

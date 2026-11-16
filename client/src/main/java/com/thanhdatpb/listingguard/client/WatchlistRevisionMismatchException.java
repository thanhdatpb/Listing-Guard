package com.thanhdatpb.listingguard.client;

/**
 * {@code 412 watchlist-revision-mismatch} (ADR-031): the client's watchlist changed after the revision the caller based its
 * change on. Nothing was stored. Read the list again, apply the change to the current one and send it with the new revision;
 * sending the same request again does not help.
 */
public class WatchlistRevisionMismatchException extends ListingGuardApiException {

    /** The problem code. */
    public static final String CODE = "watchlist-revision-mismatch";

    private static final long serialVersionUID = 1L;

    public WatchlistRevisionMismatchException(ApiProblem problem) {
        super(problem);
    }

    /** The revision the list is at now, or {@code null} when the answer did not say. */
    public Long currentRevision() {
        return problem().number("currentRevision");
    }
}

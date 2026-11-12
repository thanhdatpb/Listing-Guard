package com.thanhdatpb.listingguard.client;

/**
 * A {@code 503} whose code ends in {@code -not-configured}: the deployment does not serve this capability
 * ({@code trademark-not-configured}: no trademark catalog, so no scan ran; {@code image-checks-not-configured};
 * {@code embedding-not-configured}). Waiting does not change it, so the SDK never retries it. For a trademark check it means
 * the risk is <b>unknown, not low</b>: never report it as "no trademark problem". {@link #code()} says which capability.
 */
public class NotConfiguredException extends ListingGuardApiException {

    private static final long serialVersionUID = 1L;

    public NotConfiguredException(ApiProblem problem) {
        super(problem);
    }
}

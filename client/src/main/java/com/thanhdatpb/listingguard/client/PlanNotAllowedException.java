package com.thanhdatpb.listingguard.client;

/**
 * {@code 403 plan-not-allowed} (ADR-013): the client's plan does not include the pack of the endpoint. Retrying does not
 * help; the operator can give the client a plan that includes it.
 */
public class PlanNotAllowedException extends ListingGuardApiException {

    private static final long serialVersionUID = 1L;

    public PlanNotAllowedException(ApiProblem problem) {
        super(problem);
    }

    /** The client's plan, or {@code null}. */
    public String plan() {
        return problem().text("plan");
    }

    /** The pack the endpoint needs (for example {@code trademark-risk}), or {@code null}. */
    public String pack() {
        return problem().text("pack");
    }
}

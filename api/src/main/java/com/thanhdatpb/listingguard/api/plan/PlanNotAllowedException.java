package com.thanhdatpb.listingguard.api.plan;

/** The client's plan does not include the pack (ADR-013). Answered as 403 {@code plan-not-allowed}. */
public class PlanNotAllowedException extends RuntimeException {

    public static final String CODE = "plan-not-allowed";

    private final String plan;
    private final Pack pack;

    public PlanNotAllowedException(String plan, Pack pack) {
        super("The " + plan + " plan does not include " + pack.id() + ". Ask the operator for a plan that does.");
        this.plan = plan;
        this.pack = pack;
    }

    public String plan() {
        return plan;
    }

    public Pack pack() {
        return pack;
    }
}

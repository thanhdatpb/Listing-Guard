package com.thanhdatpb.listingguard.api.review;

public enum ReviewCaseStatus {
    OPEN, IN_REVIEW, RESOLVED, DISMISSED;

    boolean permits(ReviewCaseStatus next) {
        return switch (this) {
            case OPEN -> next == IN_REVIEW;
            case IN_REVIEW -> next == RESOLVED || next == DISMISSED;
            case RESOLVED, DISMISSED -> false;
        };
    }

    ReviewDecision.Reason requiredReason() {
        return switch (this) {
            case OPEN, IN_REVIEW -> null;
            case RESOLVED -> ReviewDecision.Reason.FIX_CONFIRMED;
            case DISMISSED -> ReviewDecision.Reason.NO_ACTION_REQUIRED;
        };
    }
}

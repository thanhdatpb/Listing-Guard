package com.thanhdatpb.listingguard.rag.analysis;

import java.util.List;

/** The findings that passed verification, in the model's order, and the counts for the response and the log. */
public record VerificationResult(List<PolicyFinding> findings, VerificationStats stats) {

    public VerificationResult {
        findings = List.copyOf(findings);
    }
}

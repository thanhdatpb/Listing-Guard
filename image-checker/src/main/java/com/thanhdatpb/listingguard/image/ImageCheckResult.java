package com.thanhdatpb.listingguard.image;

import java.util.List;

public record ImageCheckResult(String inputHash, String rulesetVersion, String analysisVersion,
                               ImageItemResult.Completeness completeness, String summary,
                               List<ImageItemResult> images, List<ImageFinding> findings, List<String> failedRules) {
    public ImageCheckResult {
        images = List.copyOf(images);
        findings = List.copyOf(findings);
        failedRules = List.copyOf(failedRules);
    }
}

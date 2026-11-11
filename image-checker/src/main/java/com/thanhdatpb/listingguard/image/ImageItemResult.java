package com.thanhdatpb.listingguard.image;

import java.util.List;

public record ImageItemResult(int ordinal, String imageHash, ImageRole role, ImageSource source,
                              Completeness completeness, ImageMetadata metadata,
                              List<ImageFinding> findings, List<String> failedRules) {
    public ImageItemResult {
        findings = List.copyOf(findings);
        failedRules = List.copyOf(failedRules);
    }
    public enum Completeness { COMPLETE, INCOMPLETE }
}

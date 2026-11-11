package com.thanhdatpb.listingguard.image;

import com.thanhdatpb.listingguard.core.finding.Severity;

/** Bounded, client-safe finding. Does not contain image bytes or a source filename. */
public record ImageFinding(String ruleId, Severity severity, String message, String evidence) { }

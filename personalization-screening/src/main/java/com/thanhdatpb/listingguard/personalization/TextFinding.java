package com.thanhdatpb.listingguard.personalization;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.personalization.config.SourceStatus;

/**
 * One thing a character rule found in one value (ADR-016). {@code kind} is the case inside the rule and, like
 * {@code ruleId}, is stable. {@code valueIndex} counts the cleaned list of values, zero-based; {@code field} is
 * {@code personalization[valueIndex]}. {@code start} and {@code end} are UTF-16 code units in the value's text (NFC, not
 * stripped), {@code end} exclusive. {@code value} is the offending characters cut to the ruleset's limit and made
 * printable, or null when no text of the buyer is to be kept. {@code message} and {@code suggestion} are Vietnamese, cite
 * the real characters, positions and numbers, and propose no replacement.
 */
public record TextFinding(String ruleId, String kind, Severity severity, SourceStatus sourceStatus, int valueIndex,
                          String field, String label, int start, int end, String value, String message,
                          String suggestion) {
}

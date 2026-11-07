package com.thanhdatpb.listingguard.personalization.rules;

import com.thanhdatpb.listingguard.personalization.TextFinding;
import java.util.List;

/**
 * One character rule (ADR-016). It reads the context, returns its findings for every value, and reads nothing else: no
 * limit, class or message is in Java (ADR-001). A rule that throws is reported by the screener, which keeps the findings of
 * the others.
 */
public interface TextRule {

    String id();

    List<TextFinding> apply(RuleContext context);
}

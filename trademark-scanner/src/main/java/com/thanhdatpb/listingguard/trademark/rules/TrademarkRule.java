package com.thanhdatpb.listingguard.trademark.rules;

import java.util.List;

/**
 * One rule of the trademark pack. Rules run in the order the ruleset declares them, each reading what the earlier
 * ones found. A rule throws when it cannot do its work; the scanner then reports the rule as failed and keeps the
 * candidates it had before the rule ran.
 */
public interface TrademarkRule {

    /** The rule id: a key of {@code rules} in the ruleset. */
    String id();

    /** The candidates after this rule: {@code soFar} plus what this rule adds or changes. */
    List<Candidate> apply(ScanContext context, List<Candidate> soFar);
}

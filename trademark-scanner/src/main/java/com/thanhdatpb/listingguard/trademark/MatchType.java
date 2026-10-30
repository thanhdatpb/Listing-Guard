package com.thanhdatpb.listingguard.trademark;

/** How a finding was found. */
public enum MatchType {
    /** One normalized token equals a one-token catalog mark. */
    EXACT,
    /** Consecutive normalized tokens equal a multi-token catalog mark. */
    PHRASE,
    /** A bounded token or n-gram is similar enough to a catalog mark; scored in Java. */
    FUZZY,
    /** Found by a context rule, not by a catalog mark. */
    CONTEXT
}

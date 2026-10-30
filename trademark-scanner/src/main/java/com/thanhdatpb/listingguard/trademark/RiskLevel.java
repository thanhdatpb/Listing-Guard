package com.thanhdatpb.listingguard.trademark;

import java.util.Collection;

/**
 * Overall or per-finding trademark risk. {@code UNKNOWN} is only ever an overall level: it means the risk could
 * not be determined (ADR-009) and is never reported as {@code LOW}.
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN;

    /** True for the levels a finding or a rule can carry: LOW, MEDIUM and HIGH. */
    public boolean isDetermined() {
        return this != UNKNOWN;
    }

    /** The higher of two determined levels. */
    public static RiskLevel higher(RiskLevel a, RiskLevel b) {
        if (!a.isDetermined() || !b.isDetermined()) {
            throw new IllegalArgumentException("UNKNOWN cannot be compared");
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** The highest determined level in {@code levels}, or null when there is none. */
    public static RiskLevel highest(Collection<RiskLevel> levels) {
        RiskLevel highest = null;
        for (RiskLevel level : levels) {
            highest = highest == null ? level : higher(highest, level);
        }
        return highest;
    }
}

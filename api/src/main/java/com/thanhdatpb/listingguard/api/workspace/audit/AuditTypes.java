package com.thanhdatpb.listingguard.api.workspace.audit;

import java.util.Locale;
import java.util.Optional;

/** The words of the background audit (ADR-061). Each enum equals the {@code CHECK} of V58 (a test holds it). */
public final class AuditTypes {

    private AuditTypes() {
    }

    /** Why a run was queued. */
    public enum Reason {
        IMPORT, SAVE, CATALOG, WATCHLIST, RULESET, SCHEDULE, MANUAL
    }

    public enum RunStatus {
        QUEUED, RUNNING, DONE, FAILED
    }

    /** What the list says of a listing. {@code NEW_RISK} wins over {@code ERROR} and {@code WARNING}. */
    public enum State {
        CLEAN, WARNING, ERROR, NEW_RISK, INCOMPLETE
    }

    /** The level of one finding: a rule's ERROR and WARNING, a trademark finding's HIGH and MEDIUM. */
    public enum Level {
        ERROR("error", 3), HIGH("high", 3), WARNING("warning", 2), MEDIUM("medium", 2);

        private final String code;
        private final int weight;

        Level(String code, int weight) {
            this.code = code;
            this.weight = weight;
        }

        public String code() {
            return code;
        }

        /** 3 for what needs fixing before anything else (error, high), 2 for a warning (warning, medium). */
        public int weight() {
            return weight;
        }

        public static Optional<Level> of(String code) {
            for (Level level : values()) {
                if (level.code.equals(code)) {
                    return Optional.of(level);
                }
            }
            return Optional.empty();
        }
    }

    /** Where a finding comes from. {@code RULE} is the listing rules; the others are the trademark finding sources. */
    public enum Source {
        RULE, CATALOG, CONTEXT, WATCHLIST
    }

    public static <E extends Enum<E>> Optional<E> parse(Class<E> type, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, text.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

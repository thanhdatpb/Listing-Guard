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

    /**
     * Why the trademark part of an audit did or did not run (ADR-063, V60 {@code trademark_reason}). The gate reads it: a part that did not run
     * because the client has no trademark set-up ({@link #NOT_CONFIGURED}, {@link #NO_PLAN}) does not hold a listing back, but the listing is
     * said to be unchecked for trademark; a part that did not run for a cause that passes or that can be mended ({@link #UNAVAILABLE},
     * {@link #INCOMPLETE}) does. A client that has a watchlist and no catalog ({@link #WATCHLIST_ONLY}) is not held back either (ADR-064 §3: it
     * chose to run on the watchlist alone), and its listings are said to be unchecked for the catalog wherever they are shown.
     */
    public enum TrademarkReason {
        /** the trademark part ran in full */
        RAN(false, false),
        /** no catalog is served and the client has no watchlist entry: nothing was set up */
        NOT_CONFIGURED(true, false),
        /** the client's plan has no trademark pack */
        NO_PLAN(true, false),
        /** the catalog is not ready (being loaded, or refused) or the check could not be answered: it may pass */
        UNAVAILABLE(false, true),
        /** the check ran and a rule of it failed */
        INCOMPLETE(false, true),
        /** the client has a watchlist and no catalog is served: only the watchlist was looked at; a HIGH hit of the watchlist still holds the listing back */
        WATCHLIST_ONLY(false, false);

        private final boolean notSetUp;
        private final boolean holdsBack;

        TrademarkReason(boolean notSetUp, boolean holdsBack) {
            this.notSetUp = notSetUp;
            this.holdsBack = holdsBack;
        }

        /** True when the client has no trademark set-up at all: the gate lets the listing go and says it was not checked for trademark. */
        public boolean notSetUp() {
            return notSetUp;
        }

        /** True when only the watchlist was looked at because no catalog is served: the listing goes, and it is said to be unchecked for the catalog (ADR-064 §3). */
        public boolean catalogUnchecked() {
            return this == WATCHLIST_ONLY;
        }

        /** True when the missing trademark part holds the listing back at the gate. */
        public boolean holdsBack() {
            return holdsBack;
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

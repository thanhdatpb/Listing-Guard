package com.thanhdatpb.listingguard.api.console;

import java.util.Locale;
import java.util.Optional;

/**
 * What a console account may do in its client (ADR-031, V38). Keep in sync with the {@code ck_account_role} constraint.
 */
public enum ConsoleRole {
    /** May change the client's shared settings, such as the trademark watchlist. */
    ADMIN,
    /** Uses the console; reads the shared settings without changing them. */
    MEMBER;

    public static Optional<ConsoleRole> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(text.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

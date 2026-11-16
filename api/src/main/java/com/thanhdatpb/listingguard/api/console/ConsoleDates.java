package com.thanhdatpb.listingguard.api.console;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * A time as the console prints it (ADR-042): the pattern is the language's own ({@code format.datetime} of the messages, "17/11/2026
 * 03:33" in Vietnamese, "Nov 17, 2026 03:33" in English), always in UTC because every column that shows one says so. A page that kept
 * the ISO text of an instant read like a log; this reads like the rest of the page.
 */
public final class ConsoleDates {

    private ConsoleDates() {
    }

    /** The instant in UTC, in the pattern of the language of the request. */
    public static String dateTime(ConsoleMessages messages, Instant instant) {
        return DateTimeFormatter.ofPattern(messages.get("format.datetime"), LocaleContextHolder.getLocale()).withZone(ZoneOffset.UTC)
                .format(instant);
    }
}

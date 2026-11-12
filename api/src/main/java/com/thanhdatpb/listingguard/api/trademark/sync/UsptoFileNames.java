package com.thanhdatpb.listingguard.api.trademark.sync;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what a USPTO bulk-data file name says (ADR-009, PR A2d): the day of a daily file, the last day and the part of a
 * backfile file. The patterns are settings (the real names are unverified). A name that is not a plain file name is never
 * used, whatever a pattern says, because it becomes a path in the store.
 */
final class UsptoFileNames {

    /** The same rule as {@code file} in {@code catalogs/manifest.yaml} and the {@code CHECK} of V32. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    /**
     * @param date the day of a daily file, or the last day a backfile covers
     * @param part the part number of a backfile file, null when the pattern has no {@code part} group
     */
    record Parsed(LocalDate date, Integer part) {
    }

    private final Pattern daily;
    private final Pattern backfile;

    UsptoFileNames(String dailyPattern, String backfilePattern) {
        this.daily = Pattern.compile(dailyPattern);
        this.backfile = Pattern.compile(backfilePattern);
    }

    static boolean plain(String name) {
        return name != null && PLAIN.matcher(name).matches();
    }

    Optional<Parsed> daily(String name) {
        return parse(daily, name);
    }

    Optional<Parsed> backfile(String name) {
        return parse(backfile, name);
    }

    private static Optional<Parsed> parse(Pattern pattern, String name) {
        if (!plain(name)) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(name);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            int year = pattern.namedGroups().containsKey("year") ? Integer.parseInt(matcher.group("year"))
                    : 2000 + Integer.parseInt(matcher.group("yy"));
            LocalDate date = LocalDate.of(year, Integer.parseInt(matcher.group("month")),
                    Integer.parseInt(matcher.group("day")));
            Integer part = pattern.namedGroups().containsKey("part") && matcher.group("part") != null
                    ? Integer.valueOf(matcher.group("part")) : null;
            return Optional.of(new Parsed(date, part));
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
    }
}

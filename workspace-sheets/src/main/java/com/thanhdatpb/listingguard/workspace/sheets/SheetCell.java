package com.thanhdatpb.listingguard.workspace.sheets;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One cell to write (ADR-063): a text, a number or blank. A number is written as a numeric cell in a workbook and in its canonical plain
 * form (no exponent, no decimal point for a whole number) in a text file; a text is written as it is, never read as a formula.
 */
public final class SheetCell {

    /** The most significant digits a number keeps exactly as an Excel cell (a double): more are written as text. */
    public static final int MAX_DIGITS = 15;

    private static final Pattern PLAIN = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final SheetCell BLANK = new SheetCell("", null);

    private final String text;
    private final BigDecimal number;

    private SheetCell(String text, BigDecimal number) {
        this.text = text;
        this.number = number;
    }

    public static SheetCell blank() {
        return BLANK;
    }

    /** A text cell; null or empty is a blank cell. */
    public static SheetCell text(String value) {
        return value == null || value.isEmpty() ? BLANK : new SheetCell(value, null);
    }

    /** A numeric cell. */
    public static SheetCell number(BigDecimal value) {
        return new SheetCell(canonical(value), value);
    }

    /**
     * The numeric cell of a text that is a plain number: an optional minus, digits without a leading zero (so a code such as {@code 007} stays
     * text), an optional decimal part, an optional exponent (Excel prints a long number so), and at most {@link #MAX_DIGITS} significant
     * digits. Anything else is empty: the caller then writes a text.
     */
    public static Optional<SheetCell> numeric(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String clean = value.strip();
        if (clean.isEmpty() || !PLAIN.matcher(clean).matches()) {
            return Optional.empty();
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(clean);
        } catch (NumberFormatException | ArithmeticException e) {
            return Optional.empty();
        }
        if (parsed.stripTrailingZeros().precision() > MAX_DIGITS || Math.abs(parsed.stripTrailingZeros().scale()) > 40) {
            return Optional.empty();
        }
        return Optional.of(number(parsed));
    }

    /** The canonical text of a number: plain digits, a whole number without a decimal point, no trailing zeros after one. */
    public static String canonical(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.signum() == 0) {
            return "0";
        }
        return stripped.toPlainString();
    }

    public boolean isBlank() {
        return number == null && text.isEmpty();
    }

    public boolean isNumber() {
        return number != null;
    }

    /** The text of a text cell, or the canonical text of a number; empty for a blank cell. */
    public String text() {
        return text;
    }

    public BigDecimal number() {
        return number;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SheetCell cell && cell.text.equals(text) && java.util.Objects.equals(cell.number, number);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(text, number);
    }

    @Override
    public String toString() {
        return number == null ? "text(" + text.length() + " chars)" : "number";
    }
}

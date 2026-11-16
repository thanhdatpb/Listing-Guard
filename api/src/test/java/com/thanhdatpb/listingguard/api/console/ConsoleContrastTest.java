package com.thanhdatpb.listingguard.api.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.brand.BrandTokens;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Text is readable on the ground it sits on in both schemes (ADR-040): at least 4.5:1 for every pair the base style sheet uses, and
 * 3:1 for the lines of a control and the mark of a problem. The colors are resolved from {@code tokens.css} and the shades
 * {@code app.css} derives, so a change of a token or of a shade that breaks a pair fails here. A new pair in app.css is added to
 * the lists below in the same change.
 */
class ConsoleContrastTest {

    private static final double TEXT = 4.5;
    private static final double GRAPHIC = 3.0;

    /** {text, ground}: each is a pair app.css puts together. */
    private static final String[][] TEXT_PAIRS = {
            {"ink", "surface-0"}, {"ink", "surface-1"}, {"ink", "hover"}, {"ink", "surface-2"}, {"ink", "warning-subtle"},
            {"ink", "danger-subtle"},
            {"ink-muted", "surface-0"}, {"ink-muted", "surface-1"}, {"ink-muted", "hover"}, {"ink-muted", "surface-2"},
            {"ink-muted", "brand-subtle"},
            {"ink-soft", "surface-0"}, {"ink-soft", "surface-1"}, {"ink-soft", "hover"}, {"ink-soft", "surface-2"},
            {"on-brand", "brand"}, {"on-brand", "brand-strong"},
            {"brand-text", "surface-0"}, {"brand-text", "surface-1"}, {"brand-text", "brand-subtle"},
            {"brand-strong", "surface-0"}, {"brand-strong", "surface-1"},
            {"danger", "danger-subtle"}, {"danger", "surface-0"}, {"danger", "surface-1"},
            {"warning", "warning-subtle"}, {"warning", "surface-0"}, {"warning", "surface-1"},
            {"success", "success-subtle"}, {"success", "surface-0"}, {"success", "surface-1"},
    };

    /** {line or mark, ground}: borders of inputs and toggles, the focus ring, the problem mark. */
    private static final String[][] GRAPHIC_PAIRS = {
            {"border-strong", "surface-1"}, {"border-strong", "surface-0"},
            {"brand", "surface-0"}, {"brand", "surface-1"}, {"brand", "brand-subtle"},
            {"accent", "surface-1"}, {"accent", "surface-0"},
    };

    private static List<String> failures(BrandTokens tokens, boolean dark, String[][] pairs, double minimum) {
        List<String> failures = new ArrayList<>();
        for (String[] pair : pairs) {
            double ratio = BrandTokens.contrast(tokens.color(dark, pair[0]), tokens.color(dark, pair[1]));
            if (ratio < minimum) {
                failures.add(String.format("%s: %s on %s is %.2f:1, needs %.1f:1", dark ? "dark" : "light", pair[0], pair[1], ratio, minimum));
            }
        }
        return failures;
    }

    @Test
    void textIsReadableOnItsGroundInTheLightScheme() {
        assertThat(failures(BrandTokens.load(), false, TEXT_PAIRS, TEXT)).isEmpty();
    }

    @Test
    void textIsReadableOnItsGroundInTheDarkScheme() {
        assertThat(failures(BrandTokens.load(), true, TEXT_PAIRS, TEXT)).isEmpty();
    }

    @Test
    void theLinesOfControlsAndTheProblemMarkAreSeenInBothSchemes() {
        BrandTokens tokens = BrandTokens.load();
        assertThat(failures(tokens, false, GRAPHIC_PAIRS, GRAPHIC)).isEmpty();
        assertThat(failures(tokens, true, GRAPHIC_PAIRS, GRAPHIC)).isEmpty();
    }

    @Test
    void theShadesTheSheetDerivesAreResolvedInBothSchemes() {
        BrandTokens tokens = BrandTokens.load();
        for (String name : List.of("hover", "surface-2", "line-soft", "ink-soft", "border-strong")) {
            assertThat(tokens.has(name)).as(name).isTrue();
            assertThat(tokens.color(true, name)).as("dark " + name).isNotEqualTo(tokens.color(false, name));
        }
    }
}

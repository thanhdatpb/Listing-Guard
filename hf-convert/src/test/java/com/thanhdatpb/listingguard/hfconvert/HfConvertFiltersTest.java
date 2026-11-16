package com.thanhdatpb.listingguard.hfconvert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The shipped filters file and the strict loader (ADR-030). */
class HfConvertFiltersTest {

    private static String shipped() throws IOException {
        try (InputStream in = HfConvertFilters.class.getResourceAsStream("/" + HfConvertFilters.DEFAULT_RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    @Test
    void theShippedFiltersAreThePrintOnDemandDefaults() {
        HfConvertFilters filters = HfConvertFilters.loadDefault();

        assertThat(filters.version()).isEqualTo("hf-convert-filters-2026-11");
        assertThat(filters.dataset()).isEqualTo("illeatmyhat/uspto-trademarks");
        // the classes the maintainer asked for (ADR-030): 25, 21, 16, 24, 14, 18, 20, 28
        assertThat(filters.niceClasses()).containsExactly(14, 16, 18, 20, 21, 24, 25, 28);
        assertThat(filters.textOnly()).isTrue();
        assertThat(filters.minMarkChars()).isEqualTo(2);
        assertThat(filters.minYear()).isNull();
        // the 16 Live/Registered codes of the dataset's lookup plus 717, and its 107 Live/Pending codes
        assertThat(filters.statusCodes().values().stream().filter(s -> s == MarkStatus.REGISTERED)).hasSize(17);
        assertThat(filters.statusCodes().values().stream().filter(s -> s == MarkStatus.PENDING)).hasSize(107);
        assertThat(filters.statusOf("717")).isEqualTo(MarkStatus.REGISTERED);
        assertThat(filters.statusOf("602")).isNull();
        assertThat(filters.statusOf(null)).isNull();
        assertThat(filters.contentHash()).hasSize(64);
    }

    @Test
    void theHashIgnoresLineEndingsAndABom() throws IOException {
        String text = shipped();
        assertThat(HfConvertFilters.parse("﻿" + text.replace("\n", "\r\n")).contentHash())
                .isEqualTo(HfConvertFilters.parse(text).contentHash());
    }

    @Test
    void refusesASourceThatClaimsToBeVerified() throws IOException {
        String text = shipped().replace("  status: unverified", "  status: verified");
        assertThatThrownBy(() -> HfConvertFilters.parse(text)).isInstanceOf(HfConvertException.class)
                .hasMessageContaining("unverified");
    }

    @Test
    void refusesACodeListedAsBothRegisteredAndPending() throws IOException {
        String text = shipped().replace("\"410\", ", "\"410\", \"700\", ");
        assertThatThrownBy(() -> HfConvertFilters.parse(text)).isInstanceOf(HfConvertException.class)
                .hasMessageContaining("700");
    }

    @Test
    void refusesUnknownKeysBadClassesAndUnquotedCodes() throws IOException {
        String text = shipped();
        assertThatThrownBy(() -> HfConvertFilters.parse(text + "extra: 1\n")).hasMessageContaining("unknown key");
        assertThatThrownBy(() -> HfConvertFilters.parse(text.replace("[14, 16,", "[46, 16,")))
                .hasMessageContaining("1 to 45");
        assertThatThrownBy(() -> HfConvertFilters.parse(text.replace("\"624\"", "624")))
                .hasMessageContaining("quoted");
        assertThatThrownBy(() -> HfConvertFilters.parse(text.replace("min_mark_chars: 2", "min_mark_chars: 0")))
                .hasMessageContaining("min_mark_chars");
        assertThatThrownBy(() -> HfConvertFilters.parse(text.replace("min_year: null", "min_year: 1700")))
                .hasMessageContaining("min_year");
    }

    @Test
    void minYearCanBeSetForOneRunAndKeepsTheFileHash() {
        HfConvertFilters filters = HfConvertFilters.loadDefault();
        assertThat(filters.withMinYear(2015).minYear()).isEqualTo(2015);
        assertThat(filters.withMinYear(2015).contentHash()).isEqualTo(filters.contentHash());
        assertThatThrownBy(() -> filters.withMinYear(3000)).isInstanceOf(HfConvertException.class);
    }
}

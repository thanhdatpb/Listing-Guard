package com.thanhdatpb.listingguard.trademark.catalog.uspto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.trademark.catalog.MarkStatus;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap.Basis;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap.Dropped;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap.Mapped;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap.Target;
import com.thanhdatpb.listingguard.trademark.catalog.uspto.UsptoStatusMap.Unmapped;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The USPTO status map (ADR-009, PR A2b). The list of codes in {@code uspto/table1-codes.txt} is the 149 codes of
 * Table 1 of the public element documentation (numbers only), so the map is checked against the table and not
 * against itself.
 */
class UsptoStatusMapTest {

    private static final String MAP = "uspto/uspto-status-map-2026-11.yaml";

    /** The codes the maintainer decided, because Table 1 does not settle them (ADR-009). */
    private static final Set<String> MAINTAINER_DECIDED = new TreeSet<>(List.of(
            // ABANDONED, no longer in force: CANCELLED
            "600", "601", "602", "603", "604", "605", "606", "607", "608", "609", "614", "618", "401", "402", "626",
            // may still be alive: PENDING
            "612", "690", "692", "693", "694", "400", "403", "715", "620", "625",
            "740", "762", "763", "764", "765", "766", "771", "772", "775", "777", "778", "779", "780", "790", "794",
            // no status of the mark: DROP
            "000", "622", "632", "969", "970", "973"));

    private final UsptoStatusMap map = UsptoStatusMap.loadClasspath(MAP);

    private static Set<String> table1Codes() throws IOException {
        try (InputStream in = UsptoStatusMapTest.class.getResourceAsStream("/uspto/table1-codes.txt")) {
            return Arrays.stream(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static String yaml(String codes) {
        return """
                version: uspto-status-map-test
                format: trademark-applications-daily V2.0
                checked_at: 2026-11-05
                source:
                  status: unverified
                  note: test
                codes:
                """ + codes;
    }

    private static final String ONE_CODE = "  \"700\": {status: REGISTERED, basis: table, meaning: \"REGISTERED\"}\n";

    @Test
    void coversEveryCodeOfTable1Exactly() throws IOException {
        Set<String> table = table1Codes();

        assertThat(table).hasSize(149);
        assertThat(map.entries().keySet()).containsExactlyInAnyOrderElementsOf(table);
    }

    @Test
    void theFileIsAVersionedMap() {
        assertThat(map.version()).isEqualTo("uspto-status-map-2026-11");
        assertThat(map.contentHash()).hasSize(64);
    }

    @Test
    void everyTargetHasTheMaintainersCount() {
        Map<Target, Long> counts = map.entries().values().stream()
                .collect(Collectors.groupingBy(UsptoStatusMap.Entry::target, Collectors.counting()));

        assertThat(counts).containsEntry(Target.REGISTERED, 9L).containsEntry(Target.EXPIRED, 1L)
                .containsEntry(Target.CANCELLED, 21L).containsEntry(Target.PENDING, 112L)
                .containsEntry(Target.DROP, 6L);
        assertThat(counts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(149L);
    }

    @Test
    void theMaintainerDecidedCodesAreExactlyTheListOfAdr009() {
        Set<String> decided = map.entries().entrySet().stream()
                .filter(e -> e.getValue().basis() == Basis.MAINTAINER)
                .map(Map.Entry::getKey).collect(Collectors.toCollection(TreeSet::new));

        assertThat(decided).containsExactlyElementsOf(MAINTAINER_DECIDED);
        assertThat(decided).hasSize(46);
    }

    @Test
    void mapsTheGroupsOfAdr009() {
        assertThat(map.outcome("700")).isEqualTo(new Mapped(MarkStatus.REGISTERED));
        assertThat(map.outcome("800")).isEqualTo(new Mapped(MarkStatus.REGISTERED));
        assertThat(map.outcome("900")).isEqualTo(new Mapped(MarkStatus.EXPIRED));
        assertThat(map.outcome("710")).isEqualTo(new Mapped(MarkStatus.CANCELLED));
        assertThat(map.outcome("686")).isEqualTo(new Mapped(MarkStatus.PENDING));
        // abandoned: certainly dead
        assertThat(map.outcome("601")).isEqualTo(new Mapped(MarkStatus.CANCELLED));
        assertThat(map.outcome("626")).isEqualTo(new Mapped(MarkStatus.CANCELLED));
        // may still be alive: warn rather than miss
        assertThat(map.outcome("403")).isEqualTo(new Mapped(MarkStatus.PENDING));
        assertThat(map.outcome("790")).isEqualTo(new Mapped(MarkStatus.PENDING));
        // no status of the mark
        assertThat(map.outcome("000")).isInstanceOf(Dropped.class);
        assertThat(map.outcome("973")).isInstanceOf(Dropped.class);
    }

    @Test
    void aCodeThatIsNotListedIsUnmappedAndNeverGuessed() {
        assertThat(map.outcome("999")).isInstanceOf(Unmapped.class);
        assertThat(map.outcome("7")).isInstanceOf(Unmapped.class);
        assertThat(map.outcome("")).isInstanceOf(Unmapped.class);
        assertThat(map.outcome(null)).isInstanceOf(Unmapped.class);
    }

    @Test
    void parsesAMinimalMap() {
        UsptoStatusMap parsed = UsptoStatusMap.parse(yaml(ONE_CODE));

        assertThat(parsed.outcome("700")).isEqualTo(new Mapped(MarkStatus.REGISTERED));
        assertThat(parsed.entries()).hasSize(1);
    }

    @Test
    void theHashIgnoresLineEndingsAndABom() {
        String text = yaml(ONE_CODE);

        assertThat(UsptoStatusMap.parse(text.replace("\n", "\r\n")).contentHash())
                .isEqualTo(UsptoStatusMap.parse(text).contentHash());
        assertThat(UsptoStatusMap.parse("﻿" + text).contentHash())
                .isEqualTo(UsptoStatusMap.parse(text).contentHash());
    }

    @Test
    void refusesASourceStatusOtherThanUnverified() {
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(ONE_CODE).replace("status: unverified", "status: verified")))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("unverified");
    }

    @Test
    void refusesAnUnknownOrMissingKey() {
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(ONE_CODE).replace("format:", "formats:")))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("formats");
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(
                "  \"700\": {status: REGISTERED, meaning: \"REGISTERED\"}\n")))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("basis");
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(
                "  \"700\": {status: REGISTERED, basis: table, meaning: \"R\", extra: 1}\n")))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("extra");
    }

    @Test
    void refusesABadCodeStatusOrBasis() {
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(
                "  \"70\": {status: REGISTERED, basis: table, meaning: \"R\"}\n")))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("three digits");
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(
                "  \"700\": {status: ABANDONED, basis: table, meaning: \"R\"}\n")))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("ABANDONED");
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(
                "  \"700\": {status: registered, basis: table, meaning: \"R\"}\n")))
                .isInstanceOf(UsptoStatusMapException.class);
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(
                "  \"700\": {status: REGISTERED, basis: guess, meaning: \"R\"}\n")))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("guess");
    }

    @Test
    void refusesADuplicateCodeAndAnEmptyMap() {
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(ONE_CODE + ONE_CODE)))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("Invalid status map YAML");
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml("  {}\n")))
                .isInstanceOf(UsptoStatusMapException.class);
    }

    @Test
    void refusesAnotherFormatAndABadVersionOrDate() {
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(ONE_CODE).replace("V2.0", "V1.0")))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("format");
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(ONE_CODE).replace("uspto-status-map-test", "Bad Version")))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("version");
        assertThatThrownBy(() -> UsptoStatusMap.parse(yaml(ONE_CODE).replace("2026-11-05", "05/11/2026")))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("checked_at");
    }

    @Test
    void aMissingFileIsReported() {
        assertThatThrownBy(() -> UsptoStatusMap.loadClasspath("uspto/does-not-exist.yaml"))
                .isInstanceOf(UsptoStatusMapException.class).hasMessageContaining("not found");
    }
}

package com.thanhdatpb.listingguard.ruleengine.readiness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.config.RulesetException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReadinessLoaderTest {

    private static final String VALID = """
            version: readiness-test-1
            checked_at: 2026-11-05
            pack_id: listing-rules
            base_score: 100
            penalties:
              ERROR: 20
              WARNING: 5
              INFO: 1
            not_ready_max_score: 59
            source:
              status: unverified
              note: Test weights.
            """;

    private static ReadinessConfig load(String yaml) {
        return ReadinessLoader.parse(yaml);
    }

    private static String replaced(String from, String to) {
        assertThat(VALID).contains(from);
        return VALID.replace(from, to);
    }

    private static void assertRejected(String yaml, String messagePart) {
        assertThatThrownBy(() -> load(yaml)).isInstanceOf(RulesetException.class).hasMessageContaining(messagePart);
    }

    // ---- the published file ------------------------------------------------------------------------------------

    @Test
    void theRealFileHoldsTheDocumentedFormula() {
        ReadinessConfig config = ReadinessLoader.loadClasspath("readiness/readiness-2026-11.yaml");

        assertThat(config.version()).isEqualTo("readiness-2026-11");
        assertThat(config.checkedAt()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(config.packId()).isEqualTo("listing-rules");
        assertThat(config.baseScore()).isEqualTo(100);
        assertThat(config.penalty(Severity.ERROR)).isEqualTo(20);
        assertThat(config.penalty(Severity.WARNING)).isEqualTo(5);
        assertThat(config.penalty(Severity.INFO)).isEqualTo(1);
        assertThat(config.notReadyMaxScore()).isEqualTo(59);
    }

    /** A published readiness version is immutable (ADR-014): if this fails, someone edited the file; add a new version. */
    @Test
    void readiness202611IsUnchanged() {
        ReadinessConfig config = ReadinessLoader.loadClasspath("readiness/readiness-2026-11.yaml");

        assertThat(config.contentHash()).isEqualTo("23d6cf7e312f5d2e0f98594b3aa8b6112f51b710ce4362ebb38a87ca962dba9e");
    }

    @Test
    void theHashIgnoresLineEndingsAndABom() {
        ReadinessConfig lf = load(VALID);

        assertThat(load(VALID.replace("\n", "\r\n")).contentHash()).isEqualTo(lf.contentHash());
        assertThat(load("﻿" + VALID).contentHash()).isEqualTo(lf.contentHash());
        assertThat(load(replaced("ERROR: 20", "ERROR: 21")).contentHash()).isNotEqualTo(lf.contentHash());
    }

    @Test
    void aMissingFileIsReported() {
        assertThatThrownBy(() -> ReadinessLoader.loadClasspath("readiness/nope.yaml"))
                .isInstanceOf(RulesetException.class).hasMessageContaining("not found");
    }

    // ---- strictness --------------------------------------------------------------------------------------------

    @Test
    void aValidFileLoads() {
        assertThat(load(VALID).version()).isEqualTo("readiness-test-1");
    }

    @Test
    void anUnknownTopLevelKeyIsRejected() {
        assertRejected(VALID + "extra: 1\n", "Unknown top-level key 'extra'");
    }

    @Test
    void aDuplicateKeyIsRejected() {
        assertRejected(VALID + "base_score: 90\n", "Invalid readiness YAML");
    }

    @Test
    void aMissingKeyIsRejected() {
        assertRejected(replaced("version: readiness-test-1\n", ""), "'version'");
        assertRejected(replaced("base_score: 100\n", ""), "'base_score'");
        assertRejected(replaced("not_ready_max_score: 59\n", ""), "'not_ready_max_score'");
        assertRejected(replaced("pack_id: listing-rules\n", ""), "'pack_id'");
    }

    @Test
    void aPenaltyForEverySeverityIsRequired() {
        assertRejected(replaced("  INFO: 1\n", ""), "missing 'INFO'");
        assertRejected(replaced("  INFO: 1\n", "  INFO: 1\n  DEBUG: 1\n"), "unknown severity 'DEBUG'");
    }

    @Test
    void numbersMustBeWholeAndInRange() {
        assertRejected(replaced("ERROR: 20", "ERROR: 20.5"), "whole number");
        assertRejected(replaced("ERROR: 20", "ERROR: twenty"), "whole number");
        assertRejected(replaced("ERROR: 20", "ERROR: -1"), "penalties.ERROR");
        assertRejected(replaced("base_score: 100", "base_score: 0"), "base_score");
        assertRejected(replaced("base_score: 100", "base_score: 101"), "base_score");
        assertRejected(replaced("not_ready_max_score: 59", "not_ready_max_score: 101"), "not_ready_max_score");
        assertRejected(replaced("not_ready_max_score: 59", "not_ready_max_score: -1"), "not_ready_max_score");
    }

    @Test
    void theSourceMustAdmitTheWeightsAreUnverified() {
        assertRejected(replaced("status: unverified", "status: verified"), "must be 'unverified'");
        assertRejected(replaced("  note: Test weights.\n", ""), "'note'");
        assertRejected(replaced("source:\n  status: unverified\n  note: Test weights.\n", ""), "'source'");
    }

    @Test
    void aBadDateOrPackIdIsRejected() {
        assertRejected(replaced("2026-11-05", "05/11/2026"), "ISO date");
        assertRejected(replaced("pack_id: listing-rules", "pack_id: Listing Rules"), "pack_id");
    }

    @Test
    void aFileThatIsNotAMappingIsRejected() {
        assertRejected("- a\n- b\n", "mapping");
        assertRejected("", "mapping");
    }
}

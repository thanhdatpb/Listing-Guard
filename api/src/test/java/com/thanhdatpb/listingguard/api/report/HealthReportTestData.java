package com.thanhdatpb.listingguard.api.report;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rows for the tests of the listing health report, written straight into the result tables with explicit timestamps, so a
 * test controls the day, the week and the expiry of every result. Every name and text is invented. A finding written with a
 * {@code secret} puts it in every column that holds text a client typed, to prove that the report never reads those.
 */
final class HealthReportTestData {

    static final String READINESS_VERSION = "readiness-2026-11";

    /** What a result stores of its publish readiness (V20). */
    record Readiness(String status, Integer score, Boolean ready, String version) {

        static Readiness none() {
            return new Readiness(null, null, null, null);
        }

        static Readiness scored(int score, boolean ready) {
            return new Readiness("SCORED", score, ready, READINESS_VERSION);
        }

        static Readiness incomplete() {
            return new Readiness("INCOMPLETE", null, false, READINESS_VERSION);
        }
    }

    private final JdbcTemplate jdbc;

    HealthReportTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static String hex() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }

    private long ruleset(String pack, String marketplace) {
        return jdbc.queryForObject("SELECT id FROM ruleset_version WHERE pack_id = ? AND marketplace_code = ? ORDER BY id DESC LIMIT 1",
                Long.class, pack, marketplace);
    }

    // ---- listing-rules

    UUID listing(UUID client, String marketplace, Instant createdAt) {
        return listing(client, marketplace, createdAt, "COMPLETED", Readiness.none());
    }

    UUID listing(UUID client, String marketplace, Instant createdAt, Readiness readiness) {
        return listing(client, marketplace, createdAt, "COMPLETED", readiness);
    }

    UUID listing(UUID client, String marketplace, Instant createdAt, String status, Readiness readiness) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO listing_check (id, client_id, marketplace_code, pack_id, content_hash, status, ruleset_version_id,
                    analysis_version, created_at, fixes_computed, readiness_score, readiness_ready, readiness_status, readiness_version)
                VALUES (?, ?, ?, 'listing-rules', ?, ?, ?, 'test-analysis', ?, ?, ?, ?, ?, ?)""",
                id, client, marketplace, hex(), status, ruleset("listing-rules", marketplace), at(createdAt), readiness.status() != null,
                readiness.score(), readiness.ready(), readiness.status(), readiness.version());
        return id;
    }

    void finding(UUID check, String severity, String ruleId) {
        finding(check, severity, ruleId, null);
    }

    void finding(UUID check, String severity, String ruleId, String secret) {
        jdbc.update("""
                INSERT INTO finding (check_id, source, severity, field, message, suggestion, value, rule_id)
                VALUES (?, 'RULE', ?, ?, ?, ?, ?, ?)""", check, severity, secret == null ? "title" : secret,
                secret == null ? "message" : secret, secret, secret, ruleId);
    }

    // ---- trademark-risk

    UUID trademark(UUID client, String marketplace, Instant createdAt, Instant expiresAt, String completeness, String riskLevel) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trademark_check (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id,
                    catalog_version_id, analysis_version, completeness, risk_level, disclaimer, created_at, expires_at)
                VALUES (?, ?, ?, 'trademark-risk', ?, ?, (SELECT id FROM trademark_catalog_version ORDER BY id LIMIT 1), ?, ?, ?, 'd', ?, ?)""",
                id, client, marketplace, hex(), ruleset("trademark-risk", marketplace), hex(), completeness, riskLevel,
                at(createdAt), at(expiresAt));
        return id;
    }

    void trademarkFinding(UUID check, int position, String ruleId, String risk, String mark) {
        trademarkFinding(check, position, ruleId, risk, mark, null);
    }

    void trademarkFinding(UUID check, int position, String ruleId, String risk, String mark, String secret) {
        jdbc.update("""
                INSERT INTO trademark_check_finding (check_id, position, rule_id, risk, field, span_start, span_end, snippet,
                    matched_mark, match_type, inside_color_phrase, message, suggestion)
                VALUES (?, ?, ?, ?, ?, 0, 1, ?, ?, ?, FALSE, ?, ?)""", check, position, ruleId, risk,
                secret == null ? "title" : secret, secret == null ? "snippet" : secret, mark, mark == null ? "CONTEXT" : "EXACT",
                secret == null ? "message" : secret, secret == null ? "suggestion" : secret);
    }

    // ---- variation-checks

    UUID variation(UUID client, String marketplace, Instant createdAt, Instant expiresAt, String completeness) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO variation_check (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id,
                    analysis_version, completeness, disclaimer, created_at, expires_at)
                VALUES (?, ?, ?, 'variation-checks', ?, ?, ?, ?, 'd', ?, ?)""", id, client, marketplace, hex(),
                ruleset("variation-checks", marketplace), hex(), completeness, at(createdAt), at(expiresAt));
        return id;
    }

    void variationFinding(UUID check, int position, String ruleId, String severity) {
        variationFinding(check, position, ruleId, severity, null);
    }

    void variationFinding(UUID check, int position, String ruleId, String severity, String secret) {
        jdbc.update("""
                INSERT INTO variation_check_finding (check_id, position, rule_id, kind, severity, source_status, field, value,
                    related_fields, message, suggestion)
                VALUES (?, ?, ?, 'k', ?, 'unverified', ?, ?, ?, ?, ?)""", check, position, ruleId, severity,
                secret == null ? "children[1].sku" : secret, secret,
                secret == null ? new String[0] : new String[] {secret}, secret == null ? "message" : secret,
                secret == null ? "suggestion" : secret);
    }

    // ---- personalization-screening

    UUID screening(UUID client, String marketplace, Instant createdAt, Instant expiresAt, String completeness,
                   String trademarkStatus, String riskLevel) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO personalization_screening (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id,
                    analysis_version, print_profile, completeness, trademark_status, trademark_risk_level, disclaimer, created_at,
                    expires_at)
                VALUES (?, ?, ?, 'personalization-screening', ?, ?, ?, 'latin_basic', ?, ?, ?, 'd', ?, ?)""", id, client, marketplace,
                hex(), ruleset("personalization-screening", marketplace), hex(), completeness, trademarkStatus, riskLevel,
                at(createdAt), at(expiresAt));
        return id;
    }

    /** A character-rule finding ({@code TEXT}). */
    void screeningFinding(UUID check, int position, String ruleId, String severity) {
        screeningFinding(check, position, ruleId, severity, null);
    }

    void screeningFinding(UUID check, int position, String ruleId, String severity, String secret) {
        jdbc.update("""
                INSERT INTO personalization_screening_finding (check_id, position, source, rule_id, kind, severity, source_status,
                    value_index, label, start_offset, end_offset, value, message, suggestion)
                VALUES (?, ?, 'TEXT', ?, 'k', ?, 'unverified', 0, ?, 0, 1, ?, ?, ?)""", check, position, ruleId, severity,
                secret == null ? "label" : secret.substring(0, Math.min(64, secret.length())), secret,
                secret == null ? "message" : secret, secret == null ? "suggestion" : secret);
    }

    /** A trademark finding of a screening ({@code TRADEMARK}): it is not counted among the character rules. */
    void screeningTrademarkFinding(UUID check, int position, String ruleId, String risk, String mark) {
        jdbc.update("""
                INSERT INTO personalization_screening_finding (check_id, position, source, rule_id, risk, match_type, value_index,
                    start_offset, end_offset, mark, message, suggestion)
                VALUES (?, ?, 'TRADEMARK', ?, ?, 'EXACT', 0, 0, 1, ?, 'message', 'suggestion')""", check, position, ruleId, risk, mark);
    }
}

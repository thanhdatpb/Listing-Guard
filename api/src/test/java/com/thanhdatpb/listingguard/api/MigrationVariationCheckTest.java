package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.variation.Completeness;
import com.thanhdatpb.listingguard.variation.config.SourceStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V23 (ADR-015): the result tables of the variation checks. A complete result is unique per cache identity and an
 * INCOMPLETE one is not; the findings go with their check; and the CHECK constraints are the Java enums they mirror.
 */
@Testcontainers
class MigrationVariationCheckTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).cleanDisabled(false)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load();
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeEach
    void emptyDatabase() {
        flyway("latest").clean();
        flyway("latest").migrate();
    }

    private static final String CLIENT = "(SELECT id FROM client WHERE name = 'v23-client')";
    private static final String RULESET = "(SELECT id FROM ruleset_version WHERE pack_id = 'variation-checks')";

    private static void seed(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO client (name) VALUES ('v23-client')");
        statement.executeUpdate("INSERT INTO ruleset_version (pack_id, marketplace_code, version, content_hash, checked_at, source) "
                + "VALUES ('variation-checks', 'AMAZON', 'variation-checks-test', 'hash', DATE '2026-11-06', 'test')");
    }

    private static String insertCheck(String id, String completeness, String hash) {
        return "INSERT INTO variation_check (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id, "
                + "analysis_version, completeness, disclaimer, created_at, expires_at) VALUES ('" + id + "', " + CLIENT
                + ", 'AMAZON', 'variation-checks', '" + hash + "', " + RULESET + ", '" + "a".repeat(64) + "', '" + completeness
                + "', 'd', now(), now() + interval '30 days')";
    }

    private static int count(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test
    void aCompleteResultIsUniquePerCacheIdentityAndAnIncompleteOneIsNot() throws Exception {
        String hash = "b".repeat(64);
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000001", "COMPLETE", hash));

            assertThatThrownBy(() -> statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000002", "COMPLETE", hash)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_variation_check_cache");
            // an INCOMPLETE result is stored for GET and never blocks anything, however many there are
            statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000003", "INCOMPLETE", hash));
            statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000004", "INCOMPLETE", hash));
            // another input is another identity
            statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000005", "COMPLETE", "c".repeat(64)));

            assertThat(count(statement, "SELECT count(*) FROM variation_check")).isEqualTo(4);
        }
    }

    @Test
    void theFindingsOfACheckAreDeletedWithIt() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000011", "COMPLETE", "d".repeat(64)));
            statement.executeUpdate("INSERT INTO variation_check_finding (check_id, position, rule_id, kind, severity, "
                    + "source_status, field, value, message, suggestion) VALUES ('00000000-0000-0000-0000-000000000011', 0, "
                    + "'duplicate_sku', 'duplicate_sku', 'WARNING', 'unverified', 'children[1].sku', 'A', 'm', 's')");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO variation_check_finding (check_id, position, rule_id, "
                    + "kind, severity, source_status, field, message, suggestion) VALUES "
                    + "('00000000-0000-0000-0000-000000000011', 0, 'r', 'k', 'INFO', 'secondary', 'f', 'm', 's')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_variation_check_finding_position");

            statement.executeUpdate("DELETE FROM variation_check WHERE id = '00000000-0000-0000-0000-000000000011'");

            assertThat(count(statement, "SELECT count(*) FROM variation_check_finding")).isZero();
        }
    }

    @Test
    void theConstraintsRefuseWhatTheEnumsDoNotHave() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            assertThatThrownBy(() -> statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000021", "UNKNOWN", "e".repeat(64))))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_variation_check_completeness");
            assertThatThrownBy(() -> statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000022", "COMPLETE", "e".repeat(64))
                    .replace("now() + interval '30 days'", "now() - interval '1 day'")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_variation_check_expiry");

            // every value of the Java enums is accepted, in the case the table keeps
            for (Completeness completeness : Completeness.values()) {
                statement.executeUpdate(insertCheck("00000000-0000-0000-0000-0000000000" + (30 + completeness.ordinal()),
                        completeness.name(), "f".repeat(63) + completeness.ordinal()));
            }
            String check = "00000000-0000-0000-0000-000000000030";
            int position = 0;
            for (Severity severity : Severity.values()) {
                for (SourceStatus status : SourceStatus.values()) {
                    statement.executeUpdate("INSERT INTO variation_check_finding (check_id, position, rule_id, kind, severity, "
                            + "source_status, field, message, suggestion) VALUES ('" + check + "', " + position++ + ", 'r', 'k', '"
                            + severity.name() + "', '" + status.yaml() + "', 'f', 'm', 's')");
                }
            }
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO variation_check_finding (check_id, position, rule_id, "
                    + "kind, severity, source_status, field, message, suggestion) VALUES ('" + check + "', 99, 'r', 'k', 'FATAL', "
                    + "'secondary', 'f', 'm', 's')")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_variation_check_finding_severity");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO variation_check_finding (check_id, position, rule_id, "
                    + "kind, severity, source_status, field, message, suggestion) VALUES ('" + check + "', 98, 'r', 'k', 'INFO', "
                    + "'SECONDARY', 'f', 'm', 's')")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_variation_check_finding_source_status");
        }
    }

    @Test
    void theEvidenceValueHasACeiling() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000041", "COMPLETE", "9".repeat(64)));

            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO variation_check_finding (check_id, position, rule_id, "
                    + "kind, severity, source_status, field, value, message, suggestion) VALUES "
                    + "('00000000-0000-0000-0000-000000000041', 0, 'r', 'k', 'INFO', 'secondary', 'f', '" + "x".repeat(501)
                    + "', 'm', 's')")).isInstanceOf(SQLException.class).hasMessageContaining("variation_check_finding_value_check");
        }
    }
}

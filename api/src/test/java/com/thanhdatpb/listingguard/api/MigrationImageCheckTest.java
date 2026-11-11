package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.image.ImageItemResult;
import com.thanhdatpb.listingguard.image.ImageRole;
import com.thanhdatpb.listingguard.image.ImageSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * V28 and V29 (ADR-008): the plan pack of the image checks and the result tables. A complete result is unique per cache
 * identity and an INCOMPLETE one is not; items and findings go with their check; the CHECK constraints are the Java enums they
 * mirror; an incomplete check is never summarized as anything but incomplete.
 */
@Testcontainers
class MigrationImageCheckTest {

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
    }

    private static List<String> column(Statement statement, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static int count(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    // ---- V28 ------------------------------------------------------------------------------------------------------

    @Test
    void v28GivesThePackToSellerAndProOnlyKeepsEveryOtherPackAndWhatAnOperatorSet() throws Exception {
        flyway("27").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_pack WHERE plan_code = 'SELLER' AND pack_id = 'batch'");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('SELLER', 'image-checks')"))
                    .as("V24's constraint does not know the pack yet")
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_plan_pack_pack_id");

            flyway("28").migrate();

            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'FREE'"))
                    .containsExactlyInAnyOrder("listing-rules");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'SELLER'"))
                    .containsExactlyInAnyOrder("listing-rules", "trademark-risk", "sheet-connector", "variation-checks",
                            "personalization-screening", "image-checks");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'PRO'"))
                    .containsExactlyInAnyOrder("listing-rules", "trademark-risk", "policy-analyzer", "batch", "sheet-connector",
                            "variation-checks", "personalization-screening", "image-checks");
            // the analysis pack id of the ruleset is not a plan pack
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', 'image-compliance')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_plan_pack_pack_id");
        }
    }

    @Test
    void v28MigratesAlsoWhenAnOperatorRemovedAPlanRow() throws Exception {
        flyway("27").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_pack WHERE plan_code = 'PRO'");
            statement.executeUpdate("DELETE FROM plan WHERE code = 'PRO'");

            flyway("28").migrate();

            assertThat(column(statement, "SELECT code FROM plan")).containsExactlyInAnyOrder("FREE", "SELLER");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'SELLER'")).contains("image-checks");
        }
    }

    @Test
    void theJavaEnumPackIsExactlyTheCheckedValues() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            for (Pack pack : Pack.values()) {
                statement.executeUpdate("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', '" + pack.id()
                        + "') ON CONFLICT DO NOTHING");
            }
            String definition = column(statement, "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = 'ck_plan_pack_pack_id'").get(0);
            assertThat(definition.chars().filter(c -> c == '\'').count() / 2).isEqualTo(Pack.values().length);
            assertThat(definition).contains("'image-checks'");
        }
    }

    // ---- V29 ------------------------------------------------------------------------------------------------------

    private static final String CLIENT = "(SELECT id FROM client WHERE name = 'v29-client')";
    private static final String RULESET = "(SELECT id FROM ruleset_version WHERE pack_id = 'image-compliance')";

    private static void seed(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO client (name) VALUES ('v29-client')");
        statement.executeUpdate("INSERT INTO ruleset_version (pack_id, marketplace_code, version, content_hash, checked_at, source) "
                + "VALUES ('image-compliance', 'AMAZON', 'amazon-image-test', 'hash', DATE '2026-11-11', 'test')");
    }

    private static String insertCheck(String id, String completeness, String summary, String hash) {
        return "INSERT INTO image_check (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id, analysis_version, "
                + "profile, completeness, summary, disclaimer, created_at, expires_at) VALUES ('" + id + "', " + CLIENT
                + ", 'AMAZON', 'image-compliance', '" + hash + "', " + RULESET + ", '" + "a".repeat(64) + "', 'default', '"
                + completeness + "', '" + summary + "', 'd', now(), now() + interval '30 days')";
    }

    private static String insertItem(String checkId, int ordinal, String role, String source, String completeness) {
        return "INSERT INTO image_check_item (check_id, ordinal, image_hash, role, source, completeness) VALUES ('" + checkId + "', "
                + ordinal + ", '" + "b".repeat(64) + "', '" + role + "', '" + source + "', '" + completeness + "')";
    }

    private static String insertFinding(String checkId, int position, String severity, String status) {
        return "INSERT INTO image_check_finding (check_id, position, rule_id, severity, source_status, evidence, message) VALUES ('"
                + checkId + "', " + position + ", 'blur', '" + severity + "', '" + status + "', 'laplacian_variance=1', 'm')";
    }

    @Test
    void aCompleteResultIsUniquePerCacheIdentityAndAnIncompleteOneIsNot() throws Exception {
        flyway("latest").migrate();
        String hash = "c".repeat(64);
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000001", "COMPLETE", "READY", hash));

            assertThatThrownBy(() -> statement.executeUpdate(
                    insertCheck("00000000-0000-0000-0000-000000000002", "COMPLETE", "READY", hash)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_image_check_cache");
            statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000003", "INCOMPLETE", "INCOMPLETE", hash));
            statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000004", "INCOMPLETE", "INCOMPLETE", hash));
            statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000005", "COMPLETE", "REVIEW", "d".repeat(64)));

            assertThat(count(statement, "SELECT count(*) FROM image_check")).isEqualTo(4);
        }
    }

    @Test
    void itemsAndFindingsGoWithTheirCheckAndAnOrdinalAndAPositionAreUnique() throws Exception {
        flyway("latest").migrate();
        String id = "00000000-0000-0000-0000-000000000011";
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            statement.executeUpdate(insertCheck(id, "COMPLETE", "REVIEW", "e".repeat(64)));
            statement.executeUpdate(insertItem(id, 0, "MAIN", "SELLER", "COMPLETE"));
            statement.executeUpdate(insertFinding(id, 0, "WARNING", "unverified"));
            statement.executeUpdate("INSERT INTO image_check_finding (check_id, item_id, position, rule_id, severity, source_status, "
                    + "evidence, message) VALUES ('" + id + "', (SELECT id FROM image_check_item WHERE check_id = '" + id
                    + "'), 1, 'blur', 'INFO', 'unverified', 'x', 'm')");

            assertThatThrownBy(() -> statement.executeUpdate(insertItem(id, 0, "ADDITIONAL", "UNKNOWN", "COMPLETE")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_image_check_item_ordinal");
            assertThatThrownBy(() -> statement.executeUpdate(insertFinding(id, 1, "INFO", "unverified")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_image_check_finding_position");
            statement.executeUpdate("DELETE FROM image_check WHERE id = '" + id + "'");

            assertThat(count(statement, "SELECT count(*) FROM image_check_item")).isZero();
            assertThat(count(statement, "SELECT count(*) FROM image_check_finding")).isZero();
        }
    }

    @Test
    void theCheckConstraintsAreTheJavaEnumsTheyMirror() throws Exception {
        flyway("latest").migrate();
        String id = "00000000-0000-0000-0000-000000000021";
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            statement.executeUpdate(insertCheck(id, "COMPLETE", "READY", "f".repeat(64)));
            int ordinal = 0;
            for (ImageRole role : ImageRole.values()) {
                for (ImageSource source : ImageSource.values()) {
                    for (ImageItemResult.Completeness completeness : ImageItemResult.Completeness.values()) {
                        statement.executeUpdate(insertItem(id, ordinal++, role.name(), source.name(), completeness.name()));
                    }
                }
            }
            int position = 0;
            for (Severity severity : Severity.values()) {
                statement.executeUpdate(insertFinding(id, position++, severity.name(), "unverified"));
            }
            for (String status : List.of("verified", "secondary", "unverified")) {
                statement.executeUpdate(insertFinding(id, position++, "INFO", status));
            }

            int next = ordinal;
            assertThatThrownBy(() -> statement.executeUpdate(insertItem(id, next, "HERO", "SELLER", "COMPLETE")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_image_check_item_role");
            assertThatThrownBy(() -> statement.executeUpdate(insertItem(id, next + 1, "MAIN", "COMPANY", "COMPLETE")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_image_check_item_source");
            assertThatThrownBy(() -> statement.executeUpdate(insertItem(id, next + 2, "MAIN", "SELLER", "PARTIAL")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_image_check_item_completeness");
            int at = position;
            assertThatThrownBy(() -> statement.executeUpdate(insertFinding(id, at, "FATAL", "unverified")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_image_check_finding_severity");
            assertThatThrownBy(() -> statement.executeUpdate(insertFinding(id, at + 1, "INFO", "guessed")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_image_check_finding_source_status");
            assertThatThrownBy(() -> statement.executeUpdate(insertCheck("00000000-0000-0000-0000-000000000022", "PARTIAL",
                    "INCOMPLETE", "1".repeat(64)))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_image_check_completeness");
        }
    }

    @Test
    void anIncompleteCheckCanNeverBeSummarizedAsAnythingButIncompleteAndMetadataIsAllOrNothing() throws Exception {
        flyway("latest").migrate();
        String id = "00000000-0000-0000-0000-000000000031";
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            assertThatThrownBy(() -> statement.executeUpdate(insertCheck(id, "INCOMPLETE", "READY", "2".repeat(64))))
                    .as("a clean summary of an incomplete analysis").isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_image_check_summary");
            assertThatThrownBy(() -> statement.executeUpdate(insertCheck(id, "COMPLETE", "READY", "3".repeat(64))
                    .replace("now() + interval '30 days'", "now() - interval '1 day'")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_image_check_expiry");

            statement.executeUpdate(insertCheck(id, "COMPLETE", "READY", "4".repeat(64)));
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO image_check_item (check_id, ordinal, image_hash, role, "
                    + "source, completeness, format) VALUES ('" + id + "', 0, '" + "b".repeat(64) + "', 'MAIN', 'SELLER', "
                    + "'COMPLETE', 'JPEG')")).isInstanceOf(SQLException.class).hasMessageContaining("ck_image_check_item_metadata");
            statement.executeUpdate("INSERT INTO image_check_item (check_id, ordinal, image_hash, role, source, completeness, format, "
                    + "width, height, orientation, had_alpha, had_icc_profile) VALUES ('" + id + "', 1, '" + "b".repeat(64)
                    + "', 'MAIN', 'SELLER', 'COMPLETE', 'JPEG', 10, 10, 1, false, false)");
        }
    }
}

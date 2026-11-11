package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.personalization.Completeness;
import com.thanhdatpb.listingguard.personalization.TrademarkStatus;
import com.thanhdatpb.listingguard.personalization.config.SourceStatus;
import com.thanhdatpb.listingguard.trademark.MatchType;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V24 and V25 (ADR-016): the plan pack of the personalization screening and its result tables. A complete result is unique per
 * cache identity and an INCOMPLETE one is not; the findings go with their screening; the CHECK constraints are the Java enums
 * they mirror; and the shape of a finding is one of the two kinds.
 */
@Testcontainers
class MigrationPersonalizationScreeningTest {

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

    // ---- V24 ------------------------------------------------------------------------------------------------------

    @Test
    void v24GivesThePackToSellerAndProOnlyKeepsEveryOtherPackAndWhatAnOperatorSet() throws Exception {
        flyway("23").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            // an operator removed a pack from SELLER before the upgrade; the migration must not put it back
            statement.executeUpdate("DELETE FROM plan_pack WHERE plan_code = 'SELLER' AND pack_id = 'batch'");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO plan_pack (plan_code, pack_id) VALUES ('SELLER', 'personalization-screening')"))
                    .as("V22's constraint does not know the pack yet")
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_plan_pack_pack_id");

            flyway("24").migrate();

            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'FREE'"))
                    .containsExactlyInAnyOrder("listing-rules");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'SELLER'"))
                    .containsExactlyInAnyOrder("listing-rules", "trademark-risk", "sheet-connector", "variation-checks",
                            "personalization-screening");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'PRO'"))
                    .containsExactlyInAnyOrder("listing-rules", "trademark-risk", "policy-analyzer", "batch",
                            "sheet-connector", "variation-checks", "personalization-screening");
            // every pack V22 accepted is still accepted, and one that is not in the enum is still refused
            for (String pack : List.of("listing-rules", "trademark-risk", "policy-analyzer", "batch", "sheet-connector",
                    "variation-checks", "personalization-screening")) {
                statement.executeUpdate("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', '" + pack
                        + "') ON CONFLICT DO NOTHING");
            }
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', 'image-compliance')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_plan_pack_pack_id");
        }
    }

    @Test
    void v24MigratesAlsoWhenAnOperatorRemovedAPlanRow() throws Exception {
        flyway("23").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_pack WHERE plan_code = 'PRO'");
            statement.executeUpdate("DELETE FROM plan WHERE code = 'PRO'");

            flyway("24").migrate();

            assertThat(column(statement, "SELECT code FROM plan")).containsExactlyInAnyOrder("FREE", "SELLER");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'SELLER'"))
                    .contains("personalization-screening");
        }
    }

    @Test
    void theJavaEnumPackIsExactlyTheCheckedValues() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            List<String> accepted = new ArrayList<>();
            for (Pack pack : Pack.values()) {
                statement.executeUpdate("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', '" + pack.id()
                        + "') ON CONFLICT DO NOTHING");
                accepted.add(pack.id());
            }
            assertThat(accepted).contains("personalization-screening");
            // the constraint lists these and no other
            String definition = column(statement, "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = 'ck_plan_pack_pack_id'").get(0);
            assertThat(definition.chars().filter(c -> c == '\'').count() / 2).isEqualTo(Pack.values().length);
        }
    }

    // ---- V25 ------------------------------------------------------------------------------------------------------

    private static final String CLIENT = "(SELECT id FROM client WHERE name = 'v25-client')";
    private static final String RULESET = "(SELECT id FROM ruleset_version WHERE pack_id = 'personalization-screening')";

    private static void seed(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO client (name) VALUES ('v25-client')");
        statement.executeUpdate("INSERT INTO ruleset_version (pack_id, marketplace_code, version, content_hash, checked_at, source) "
                + "VALUES ('personalization-screening', 'ETSY', 'personalization-screening-test', 'hash', DATE '2026-11-06', 'test')");
    }

    private static String insertScreening(String id, String completeness, String hash, String trademarkStatus, String risk) {
        return "INSERT INTO personalization_screening (id, client_id, marketplace_code, pack_id, input_hash, ruleset_version_id, "
                + "analysis_version, print_profile, completeness, trademark_status, trademark_risk_level, disclaimer, created_at, "
                + "expires_at) VALUES ('" + id + "', " + CLIENT + ", 'ETSY', 'personalization-screening', '" + hash + "', "
                + RULESET + ", '" + "a".repeat(64) + "', 'latin_basic', '" + completeness + "', '" + trademarkStatus + "', '"
                + risk + "', 'd', now(), now() + interval '30 days')";
    }

    private static String insertScreening(String id, String completeness, String hash) {
        return insertScreening(id, completeness, hash, "COMPLETE", "LOW");
    }

    private static final String TEXT_FINDING = "INSERT INTO personalization_screening_finding (check_id, position, source, "
            + "rule_id, kind, severity, source_status, value_index, label, start_offset, end_offset, value, message, suggestion) "
            + "VALUES ('%s', %d, 'TEXT', 'unsupported_characters', 'character_not_allowed', '%s', 'unverified', 0, 'name', 0, 2, "
            + "'x', 'm', 's')";

    private static final String TRADEMARK_FINDING = "INSERT INTO personalization_screening_finding (check_id, position, source, "
            + "rule_id, risk, match_type, value_index, start_offset, end_offset, mark, message, suggestion) "
            + "VALUES ('%s', %d, 'TRADEMARK', 'catalog_exact', '%s', '%s', 0, 0, 10, 'Zibblefrax', 'm', 's')";

    @Test
    void aCompleteResultIsUniquePerCacheIdentityAndAnIncompleteOneIsNot() throws Exception {
        flyway("latest").migrate();
        String hash = "b".repeat(64);
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            statement.executeUpdate(insertScreening("00000000-0000-0000-0000-000000000001", "COMPLETE", hash));

            assertThatThrownBy(() -> statement.executeUpdate(
                    insertScreening("00000000-0000-0000-0000-000000000002", "COMPLETE", hash)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_personalization_screening_cache");
            statement.executeUpdate(insertScreening("00000000-0000-0000-0000-000000000003", "INCOMPLETE", hash,
                    "NOT_CONFIGURED", "UNKNOWN"));
            statement.executeUpdate(insertScreening("00000000-0000-0000-0000-000000000004", "INCOMPLETE", hash,
                    "NOT_CONFIGURED", "UNKNOWN"));
            statement.executeUpdate(insertScreening("00000000-0000-0000-0000-000000000005", "COMPLETE", "c".repeat(64)));

            assertThat(count(statement, "SELECT count(*) FROM personalization_screening")).isEqualTo(4);
        }
    }

    @Test
    void theFindingsGoWithTheirScreeningAndAPositionIsUnique() throws Exception {
        flyway("latest").migrate();
        String id = "00000000-0000-0000-0000-000000000011";
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            statement.executeUpdate(insertScreening(id, "COMPLETE", "d".repeat(64)));
            statement.executeUpdate(TEXT_FINDING.formatted(id, 0, "WARNING"));
            statement.executeUpdate(TRADEMARK_FINDING.formatted(id, 1, "HIGH", "EXACT"));

            assertThatThrownBy(() -> statement.executeUpdate(TEXT_FINDING.formatted(id, 1, "WARNING")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_personalization_screening_finding_position");
            statement.executeUpdate("DELETE FROM personalization_screening WHERE id = '" + id + "'");

            assertThat(count(statement, "SELECT count(*) FROM personalization_screening_finding")).isZero();
        }
    }

    @Test
    void theCheckConstraintsAreTheJavaEnumsTheyMirror() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            int n = 20;
            for (Completeness completeness : Completeness.values()) {
                statement.executeUpdate(insertScreening("00000000-0000-0000-0000-0000000001" + n++, completeness.name(),
                        String.valueOf(n).repeat(32).substring(0, 64), "COMPLETE", "LOW"));
            }
            for (TrademarkStatus status : TrademarkStatus.values()) {
                String risk = status == TrademarkStatus.NOT_CONFIGURED ? "UNKNOWN" : "LOW";
                String completeness = status == TrademarkStatus.NOT_CONFIGURED ? "INCOMPLETE" : "COMPLETE";
                statement.executeUpdate(insertScreening("00000000-0000-0000-0000-0000000002" + n++, completeness,
                        String.valueOf(n).repeat(32).substring(0, 64), status.name(), risk));
            }
            for (RiskLevel risk : RiskLevel.values()) {
                statement.executeUpdate(insertScreening("00000000-0000-0000-0000-0000000003" + n++, "INCOMPLETE",
                        String.valueOf(n).repeat(32).substring(0, 64), "INCOMPLETE", risk.name()));
            }
            assertThatThrownBy(() -> statement.executeUpdate(insertScreening("00000000-0000-0000-0000-000000000090", "PARTIAL",
                    "e".repeat(64)))).isInstanceOf(SQLException.class).hasMessageContaining("ck_personalization_screening_completeness");
            assertThatThrownBy(() -> statement.executeUpdate(insertScreening("00000000-0000-0000-0000-000000000091", "COMPLETE",
                    "f".repeat(64), "SKIPPED", "LOW"))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_personalization_screening_trademark_status");
            assertThatThrownBy(() -> statement.executeUpdate(insertScreening("00000000-0000-0000-0000-000000000092", "COMPLETE",
                    "1".repeat(64), "COMPLETE", "SEVERE"))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_personalization_screening_risk_level");
            assertThatThrownBy(() -> statement.executeUpdate(insertScreening("00000000-0000-0000-0000-000000000093", "COMPLETE",
                    "2".repeat(64)).replace("now() + interval '30 days'", "now() - interval '1 day'")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_personalization_screening_expiry");

            String id = "00000000-0000-0000-0000-000000000094";
            statement.executeUpdate(insertScreening(id, "COMPLETE", "3".repeat(64)));
            int position = 0;
            for (Severity severity : Severity.values()) {
                statement.executeUpdate(TEXT_FINDING.formatted(id, position++, severity.name()));
            }
            for (MatchType type : MatchType.values()) {
                statement.executeUpdate(TRADEMARK_FINDING.formatted(id, position++, "MEDIUM", type.name()));
            }
            assertThatThrownBy(() -> statement.executeUpdate(TEXT_FINDING.formatted(id, 50, "FATAL")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_personalization_screening_finding_severity");
            assertThatThrownBy(() -> statement.executeUpdate(TRADEMARK_FINDING.formatted(id, 51, "LOW", "SOUNDEX")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_personalization_screening_finding_match_type");
            assertThatThrownBy(() -> statement.executeUpdate(TRADEMARK_FINDING.formatted(id, 52, "UNKNOWN", "EXACT")))
                    .as("UNKNOWN is an overall level, never the risk of one finding")
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_personalization_screening_finding_risk");
            for (SourceStatus status : SourceStatus.values()) {
                statement.executeUpdate(TEXT_FINDING.formatted(id, 60 + status.ordinal(), "INFO")
                        .replace("'unverified'", "'" + status.yaml() + "'"));
            }
            assertThatThrownBy(() -> statement.executeUpdate(TEXT_FINDING.formatted(id, 70, "INFO")
                    .replace("'unverified'", "'official'"))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_personalization_screening_finding_source_status");
        }
    }

    @Test
    void aNotConfiguredScanIsNeverACleanResult() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);

            assertThatThrownBy(() -> statement.executeUpdate(insertScreening("00000000-0000-0000-0000-000000000101", "COMPLETE",
                    "4".repeat(64), "NOT_CONFIGURED", "UNKNOWN"))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_personalization_screening_not_configured");
            assertThatThrownBy(() -> statement.executeUpdate(insertScreening("00000000-0000-0000-0000-000000000102", "INCOMPLETE",
                    "5".repeat(64), "NOT_CONFIGURED", "LOW"))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_personalization_screening_not_configured");
        }
    }

    @Test
    void aFindingIsOneOfTheTwoKindsAndNothingElse() throws Exception {
        flyway("latest").migrate();
        String id = "00000000-0000-0000-0000-000000000111";
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            statement.executeUpdate(insertScreening(id, "COMPLETE", "6".repeat(64)));

            // a TEXT finding with a risk, a TRADEMARK finding with a severity, and a finding of neither kind are refused
            assertThatThrownBy(() -> statement.executeUpdate(TEXT_FINDING.formatted(id, 0, "WARNING")
                    .replace("rule_id, kind", "rule_id, risk, match_type, kind").replace("'unsupported_characters',",
                            "'unsupported_characters', 'HIGH', 'EXACT',")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_personalization_screening_finding_shape");
            assertThatThrownBy(() -> statement.executeUpdate(TRADEMARK_FINDING.formatted(id, 1, "HIGH", "EXACT")
                    .replace("rule_id, risk", "rule_id, severity, risk").replace("'catalog_exact',", "'catalog_exact', 'INFO',")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_personalization_screening_finding_shape");
            assertThatThrownBy(() -> statement.executeUpdate(TEXT_FINDING.formatted(id, 2, "INFO").replace("'TEXT'", "'OTHER'")))
                    .as("refused by the shape check and by the source check; the database reports the first one")
                    .isInstanceOf(SQLException.class).hasMessageContaining("violates check constraint");
            assertThatThrownBy(() -> statement.executeUpdate(TEXT_FINDING.formatted(id, 3, "INFO").replace("0, 2,", "5, 2,")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_personalization_screening_finding_span");
        }
    }

    @Test
    void theEvidenceHasCeilingsInTheTable() throws Exception {
        flyway("latest").migrate();
        String id = "00000000-0000-0000-0000-000000000121";
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            seed(statement);
            statement.executeUpdate(insertScreening(id, "COMPLETE", "7".repeat(64)));

            assertThatThrownBy(() -> statement.executeUpdate(TEXT_FINDING.formatted(id, 0, "INFO")
                    .replace("'x'", "'" + "v".repeat(501) + "'"))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("personalization_screening_finding_value_check");
            assertThatThrownBy(() -> statement.executeUpdate(TEXT_FINDING.formatted(id, 1, "INFO")
                    .replace("'name'", "'" + "l".repeat(65) + "'"))).isInstanceOf(SQLException.class)
                    .hasMessageContaining("personalization_screening_finding_label_check");
        }
    }
}

package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanCode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V19 (ADR-013): the plans and their packs are seeded as the ADR says, every client that exists becomes FREE, the CHECK
 * constraints are the Java enums {@code PlanCode} and {@code Pack}, and the usage table only holds whole months.
 */
@Testcontainers
class MigrationPlansTest {

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

    private static Set<String> column(Statement statement, String sql) throws SQLException {
        Set<String> values = new TreeSet<>();
        try (ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    @Test
    void everyExistingClientBecomesFreeAndTheSeedIsTheOneInTheAdr() throws Exception {
        flyway("18").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO client (name) VALUES ('before-plans-a'), ('before-plans-b')");

            flyway("19").migrate();

            assertThat(column(statement, "SELECT DISTINCT plan_code FROM client")).containsExactly("FREE");
            assertThat(column(statement, "SELECT name FROM client WHERE plan_code = 'FREE'"))
                    .contains("before-plans-a", "before-plans-b", "legacy");

            Map<String, String> quotas = new LinkedHashMap<>();
            try (ResultSet rows = statement.executeQuery("SELECT code, monthly_quota FROM plan ORDER BY monthly_quota")) {
                while (rows.next()) {
                    quotas.put(rows.getString(1), rows.getString(2));
                }
            }
            assertThat(quotas).containsExactly(Map.entry("FREE", "20"), Map.entry("SELLER", "1000"), Map.entry("PRO", "6000"));

            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'FREE'"))
                    .containsExactlyInAnyOrder("listing-rules");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'SELLER'"))
                    .containsExactlyInAnyOrder("listing-rules", "trademark-risk", "batch", "sheet-connector");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'PRO'"))
                    .containsExactlyInAnyOrder("listing-rules", "trademark-risk", "policy-analyzer", "batch", "sheet-connector");
        }
    }

    @Test
    void v22GivesVariationChecksToSellerAndProOnlyAndKeepsWhatAnOperatorSet() throws Exception {
        flyway("21").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            // an operator removed a pack from SELLER before the upgrade; the migration must not put it back
            statement.executeUpdate("DELETE FROM plan_pack WHERE plan_code = 'SELLER' AND pack_id = 'batch'");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO plan_pack (plan_code, pack_id) VALUES ('SELLER', 'variation-checks')"))
                    .as("V19's constraint does not know the pack yet")
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_plan_pack_pack_id");

            flyway("22").migrate();

            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'FREE'"))
                    .containsExactlyInAnyOrder("listing-rules");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'SELLER'"))
                    .containsExactlyInAnyOrder("listing-rules", "trademark-risk", "sheet-connector", "variation-checks");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'PRO'"))
                    .containsExactlyInAnyOrder("listing-rules", "trademark-risk", "policy-analyzer", "batch",
                            "sheet-connector", "variation-checks");
            // the constraint still refuses a pack that is not in the enum
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', 'image-compliance')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_plan_pack_pack_id");
        }
    }

    @Test
    void v22MigratesAlsoWhenAnOperatorRemovedAPlanRow() throws Exception {
        flyway("21").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM plan_pack WHERE plan_code = 'PRO'");
            statement.executeUpdate("DELETE FROM plan WHERE code = 'PRO'");

            flyway("22").migrate();

            assertThat(column(statement, "SELECT code FROM plan")).containsExactlyInAnyOrder("FREE", "SELLER");
            assertThat(column(statement, "SELECT pack_id FROM plan_pack WHERE plan_code = 'SELLER'")).contains("variation-checks");
        }
    }

    @Test
    void theJavaEnumsAreExactlyTheSeededAndCheckedValues() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            Set<String> planCodes = new HashSet<>();
            Arrays.stream(PlanCode.values()).forEach(code -> planCodes.add(code.name()));
            assertThat(column(statement, "SELECT code FROM plan")).isEqualTo(new TreeSet<>(planCodes));

            Set<String> packIds = new TreeSet<>(Pack.allIds());
            assertThat(column(statement, "SELECT DISTINCT pack_id FROM plan_pack")).isEqualTo(packIds);

            // the constraints accept every enum value (the row conflicts afterwards, which is fine) and nothing else
            for (String pack : packIds) {
                statement.executeUpdate("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', '" + pack + "') ON CONFLICT DO NOTHING");
            }
            for (String code : planCodes) {
                statement.executeUpdate("INSERT INTO plan (code, monthly_quota) VALUES ('" + code + "', 1) ON CONFLICT DO NOTHING");
            }
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO plan (code, monthly_quota) VALUES ('GOLD', 5)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_plan_code");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', 'image-compliance')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_plan_pack_pack_id");
        }
    }

    @Test
    void aQuotaIsPositiveOrUnlimitedAndAClientInsertedWithoutAPlanIsFree() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE plan SET monthly_quota = 0 WHERE code = 'FREE'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_plan_monthly_quota");
            statement.executeUpdate("UPDATE plan SET monthly_quota = NULL WHERE code = 'PRO'");
            assertThat(column(statement, "SELECT coalesce(monthly_quota::text, 'unlimited') FROM plan WHERE code = 'PRO'"))
                    .containsExactly("unlimited");

            // what an instance that predates the column still does during a rollout
            statement.executeUpdate("INSERT INTO client (name) VALUES ('old-binary-client')");
            assertThat(column(statement, "SELECT plan_code FROM client WHERE name = 'old-binary-client'")).containsExactly("FREE");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO client (name, plan_code) VALUES ('no-such-plan', 'GOLD')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("fk_client_plan");
        }
    }

    @Test
    void theUsageTableHoldsWholeMonthsAndNeverGoesBelowZero() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO client (name) VALUES ('usage-client')");
            String client = "(SELECT id FROM client WHERE name = 'usage-client')";

            statement.executeUpdate("INSERT INTO client_usage (client_id, period_start, used) VALUES (" + client + ", DATE '2026-11-01', 3)");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO client_usage (client_id, period_start, used) VALUES (" + client + ", DATE '2026-12-15', 1)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_client_usage_period_start");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO client_usage (client_id, period_start, used) VALUES (" + client + ", DATE '2026-11-01', 1)"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("client_usage_pkey");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE client_usage SET used = -1"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ck_client_usage_used");
        }
    }
}

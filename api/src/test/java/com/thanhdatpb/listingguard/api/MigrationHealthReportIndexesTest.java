package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V26 and V27 (ADR-018): the indexes the health report reads a client's results by. V26 builds them concurrently outside a
 * transaction, V27 refuses to go on while one of them is missing or invalid, and neither changes data.
 */
@Testcontainers
class MigrationHealthReportIndexesTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static final Map<String, String> INDEXES = Map.of(
            "idx_listing_check_client_created", "listing_check",
            "idx_trademark_check_client_created", "trademark_check",
            "idx_variation_check_client_created", "variation_check",
            "idx_personalization_screening_client_created", "personalization_screening");

    private static Flyway flyway(String target, Callback... callbacks) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).cleanDisabled(false).callbacks(callbacks)
                // CREATE INDEX CONCURRENTLY waits for every open transaction, so Flyway's own lock must not hold one
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load();
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    @BeforeEach
    void emptyDatabase() {
        flyway("latest").clean();
    }

    @Test
    void theFourIndexesExistAreValidAndServeTheClientAndTheCreationTime() throws SQLException {
        flyway("latest").migrate();

        try (Connection connection = open()) {
            for (Map.Entry<String, String> index : INDEXES.entrySet()) {
                assertThat(scalar(connection, "SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE c.relname = '" + index.getKey() + "' AND i.indisvalid AND NOT i.indisunique")).isEqualTo("1");
                assertThat(scalar(connection, "SELECT indexdef FROM pg_indexes WHERE indexname = '" + index.getKey() + "'"))
                        .contains("ON public." + index.getValue() + " ").contains("(client_id, created_at)");
            }
        }
    }

    @Test
    void v26RunsOutsideATransactionAndV27InsideOne() throws IOException {
        TransactionRecorder recorder = new TransactionRecorder();

        flyway("latest", recorder).migrate();

        assertThat(recorder.outsideTransaction).containsEntry("26", true).containsEntry("27", false);
        assertThat(text("db/migration/V26__health_report_indexes.sql.conf")).contains("executeInTransaction=false");
        // the statements only: the comment explains why there is no IF NOT EXISTS, so it names it
        String v26 = String.join("\n", text("db/migration/V26__health_report_indexes.sql").lines()
                .filter(line -> !line.startsWith("--")).toList());
        assertThat(v26).contains("CREATE INDEX CONCURRENTLY").doesNotContain("IF NOT EXISTS");
        assertThat(text("db/migration/V27__health_report_indexes_verify.sql")).doesNotContain("CONCURRENTLY");
    }

    @Test
    void theMigrationsLeaveExistingDataAsItWas() throws SQLException {
        flyway("25").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO client (name) VALUES ('v26-client')");
            statement.executeUpdate("INSERT INTO ruleset_version (pack_id, marketplace_code, version, content_hash, checked_at, source) "
                    + "VALUES ('listing-rules', 'ETSY', 'etsy-v26-test', 'hash', DATE '2026-11-07', 'test')");
            statement.executeUpdate("INSERT INTO listing_check (client_id, marketplace_code, pack_id, content_hash, status, "
                    + "ruleset_version_id, analysis_version) VALUES ((SELECT id FROM client WHERE name = 'v26-client'), 'ETSY', "
                    + "'listing-rules', 'h', 'COMPLETED', (SELECT id FROM ruleset_version WHERE version = 'etsy-v26-test'), 'etsy-v26-test')");

            flyway("latest").migrate();

            assertThat(scalar(connection, "SELECT count(*) FROM listing_check")).isEqualTo("1");
            assertThat(scalar(connection, "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_listing_check_client_created'"))
                    .isEqualTo("1");
        }
    }

    @Test
    void v27StopsWhileAnIndexIsInvalidOrMissingAndChangesNothing() throws SQLException {
        flyway("26").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'idx_trademark_check_client_created'::regclass");

            assertThatThrownBy(() -> flyway("latest").migrate()).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("idx_trademark_check_client_created is missing or invalid");
            assertThat(scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = '27' AND success"))
                    .isEqualTo("0");

            // the documented way out: drop the invalid index, clear the failed row, build it again, and V27 passes
            statement.execute("DROP INDEX idx_trademark_check_client_created");
            assertThatThrownBy(() -> flyway("latest").migrate()).isInstanceOf(FlywayException.class)
                    .hasMessageContaining("idx_trademark_check_client_created is missing or invalid");
            statement.execute("DELETE FROM flyway_schema_history WHERE success = false");
            statement.execute("CREATE INDEX idx_trademark_check_client_created ON trademark_check (client_id, created_at)");

            flyway("latest").migrate();

            assertThat(scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = '27' AND success"))
                    .isEqualTo("1");
        }
    }

    private static String text(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }

    /** Records, per migration version, whether Flyway ran it with autocommit on, which is how a migration outside a transaction runs. */
    private static final class TransactionRecorder implements Callback {
        final Map<String, Boolean> outsideTransaction = new LinkedHashMap<>();

        @Override
        public boolean supports(Event event, Context context) {
            return event == Event.BEFORE_EACH_MIGRATE;
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return false; // otherwise Flyway wraps the callback in its own transaction and autocommit is always off
        }

        @Override
        public void handle(Event event, Context context) {
            try {
                outsideTransaction.put(context.getMigrationInfo().getVersion().getVersion(),
                        context.getConnection().getAutoCommit());
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String getCallbackName() {
            return "transaction-recorder";
        }
    }
}

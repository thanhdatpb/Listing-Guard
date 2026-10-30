package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** V15 (ADR-009, PR A2): the pg_trgm extension and the append-only trademark catalog tables. */
@Testcontainers
class MigrationTrademarkCatalogTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static Flyway flyway(String user, String password, String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), user, password)
                .locations("classpath:db/migration").target(target).cleanDisabled(false)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load();
    }

    private static Flyway flyway(String target) {
        return flyway(POSTGRES.getUsername(), POSTGRES.getPassword(), target);
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeEach
    void emptyDatabase() throws SQLException {
        flyway("latest").clean();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP EXTENSION IF EXISTS pg_trgm CASCADE");
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static long insertVersion(Connection connection, String version) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO trademark_catalog_version (version, label, source_name, source_reference, source_license,
                    source_date, file_sha256, format_version, filters_json, pack_id, ruleset_version,
                    ruleset_content_hash, normalization_version, records_read, records_filtered, records_merged,
                    records_loaded)
                VALUES (?, 'label', 'synthetic', 'ref', 'test', current_date, repeat('a', 64), 'jsonl-1', '{}',
                    'trademark-risk', 'v', repeat('b', 64), 'normalization-1', 3, 1, 0, 2) RETURNING id
                """)) {
            insert.setString(1, version);
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void insertEntry(Connection connection, long versionId, String normalized, String status)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status, source_id)
                VALUES (?, ?, ?, ARRAY[25, 35]::smallint[], ?, 's1')
                """)) {
            insert.setLong(1, versionId);
            insert.setString(2, normalized);
            insert.setString(3, normalized);
            insert.setString(4, status);
            insert.executeUpdate();
        }
    }

    @Test
    void createsPgTrgmAndTheCatalogTables() throws SQLException {
        flyway("latest").migrate();

        try (Connection connection = open()) {
            assertThat(scalar(connection, "SELECT extversion FROM pg_extension WHERE extname = 'pg_trgm'"))
                    .matches("\\d+\\.\\d+");
            assertThat(scalar(connection, """
                    SELECT count(*) FROM pg_indexes WHERE tablename = 'trademark_entry'
                      AND indexname = 'idx_trademark_entry_normalized_trgm'
                      AND indexdef LIKE '%USING gin (normalized gin_trgm_ops)%'""")).isEqualTo("1");
            assertThat(scalar(connection, "SELECT 'zorvex' % 'zorvx'")).isEqualTo("t");
        }
    }

    @Test
    void theMigrationFailsVisiblyWhenTheRoleMayNotCreateTheExtension() throws SQLException {
        flyway("14").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ROLE IF EXISTS limited_migrator");
            statement.execute("CREATE ROLE limited_migrator LOGIN PASSWORD 'limited'");
            statement.execute("REVOKE CREATE ON DATABASE " + POSTGRES.getDatabaseName() + " FROM PUBLIC");
            statement.execute("GRANT ALL ON SCHEMA public TO limited_migrator");
            statement.execute("GRANT ALL ON ALL TABLES IN SCHEMA public TO limited_migrator");
            statement.execute("GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO limited_migrator");
        }

        assertThatThrownBy(() -> flyway("limited_migrator", "limited", "15").migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining("pg_trgm");

        try (Connection connection = open()) {
            assertThat(scalar(connection, "SELECT to_regclass('trademark_entry') IS NULL")).isEqualTo("t");
            assertThat(scalar(connection, "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'")).isEqualTo("0");
        }
    }

    @Test
    void anEntryIsUniquePerCatalogVersionAndNormalizedMark() throws SQLException {
        flyway("latest").migrate();
        try (Connection connection = open()) {
            long first = insertVersion(connection, "v1");
            long second = insertVersion(connection, "v2");
            insertEntry(connection, first, "zorvex", "REGISTERED");
            insertEntry(connection, second, "zorvex", "REGISTERED");

            assertThatThrownBy(() -> insertEntry(connection, first, "zorvex", "PENDING"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_trademark_entry");
            assertThatThrownBy(() -> insertVersion(connection, "v1"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_trademark_catalog_version");
        }
    }

    @Test
    void anEntryNeedsAKnownStatusAndAtLeastOneClass() throws SQLException {
        flyway("latest").migrate();
        try (Connection connection = open()) {
            long version = insertVersion(connection, "v1");

            assertThatThrownBy(() -> insertEntry(connection, version, "zorvex", "FAMOUS"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("trademark_entry_status_check");
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status,
                            source_id) VALUES (%d, 'x', 'x', ARRAY[]::smallint[], 'REGISTERED', 's')""".formatted(version)))
                        .isInstanceOf(SQLException.class).hasMessageContaining("ck_trademark_entry_classes");
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status,
                            source_id) VALUES (%d, '', 'x', ARRAY[25]::smallint[], 'REGISTERED', 's')""".formatted(version)))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void aCatalogVersionAndItsEntriesCannotBeUpdated() throws SQLException {
        flyway("latest").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            long version = insertVersion(connection, "v1");
            insertEntry(connection, version, "zorvex", "REGISTERED");

            assertThatThrownBy(() -> statement.executeUpdate("UPDATE trademark_catalog_version SET label = 'x'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE trademark_entry SET display = 'x'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThat(scalar(connection, "SELECT label FROM trademark_catalog_version")).isEqualTo("label");
        }
    }

    @Test
    void theTrigramIndexServesTheSimilarityOperator() throws SQLException {
        flyway("latest").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            long version = insertVersion(connection, "v1");
            statement.execute("""
                    INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status, source_id)
                    SELECT %d, md5(g::text) || ' brand', 'x', ARRAY[25]::smallint[], 'REGISTERED', 's'
                    FROM generate_series(1, 20000) g""".formatted(version));
            insertEntry(connection, version, "zorvex", "REGISTERED");
            // the btree index of the unique constraint is the other index the planner could scan; leave only the trigram one
            statement.execute("ALTER TABLE trademark_entry DROP CONSTRAINT uq_trademark_entry");
            statement.execute("ANALYZE trademark_entry");
            statement.execute("SET enable_seqscan = off");

            StringBuilder plan = new StringBuilder();
            try (ResultSet rows = statement.executeQuery(
                    "EXPLAIN SELECT normalized FROM trademark_entry WHERE normalized % 'zorvx'")) {
                while (rows.next()) {
                    plan.append(rows.getString(1)).append('\n');
                }
            }

            assertThat(plan.toString()).contains("idx_trademark_entry_normalized_trgm");
        }
    }
}

package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
                    source_date, file_sha256, format_version, filters_json, pack_id,
                    normalization_version, normalization_hash, records_read, records_filtered, records_merged,
                    records_loaded)
                VALUES (?, 'label', 'synthetic', 'ref', 'test', current_date, repeat('a', 64), 'jsonl-1', '{}',
                    'trademark-risk', 'normalization-1', repeat('b', 64), 3, 1, 0, 2) RETURNING id
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
                INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status, source_ids)
                VALUES (?, ?, ?, ARRAY[25, 35]::smallint[], ?, ARRAY['s1'])
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
                            source_ids) VALUES (%d, 'x', 'x', ARRAY[]::smallint[], 'REGISTERED', ARRAY['s'])""".formatted(version)))
                        .isInstanceOf(SQLException.class).hasMessageContaining("ck_trademark_entry_classes");
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status,
                            source_ids) VALUES (%d, 'y', 'y', ARRAY[25]::smallint[], 'REGISTERED', ARRAY[]::text[])""".formatted(version)))
                        .isInstanceOf(SQLException.class).hasMessageContaining("ck_trademark_entry_source_ids");
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status,
                            source_ids) VALUES (%d, '', 'x', ARRAY[25]::smallint[], 'REGISTERED', ARRAY['s'])""".formatted(version)))
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
                    INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status, source_ids)
                    SELECT %d, md5(g::text) || ' brand', 'x', ARRAY[25]::smallint[], 'REGISTERED', ARRAY['s']
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

    // --- V16: the normalization identity and the source ids, on top of the V15 that is already applied ---

    private static void insertV15Catalog(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO trademark_catalog_version (version, label, source_name, source_reference,
                        source_license, source_date, file_sha256, format_version, filters_json, pack_id, ruleset_version,
                        ruleset_content_hash, normalization_version, records_read, records_filtered, records_merged,
                        records_loaded)
                    VALUES ('old-1', 'old', 'synthetic', 'ref', 'test', current_date, repeat('a', 64), 'jsonl-1', '{}',
                        'trademark-risk', 'trademark-risk-2026-11', repeat('b', 64), 'normalization-1', 1, 0, 0, 1)""");
            statement.execute("""
                    INSERT INTO trademark_entry (catalog_version_id, normalized, display, nice_classes, status, source_id)
                    SELECT id, 'zorvex', 'Zorvex', ARRAY[25]::smallint[], 'REGISTERED', 'S-1'
                    FROM trademark_catalog_version WHERE version = 'old-1'""");
        }
    }

    private static List<String> columns(Connection connection, String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT column_name FROM information_schema.columns "
                     + "WHERE table_name = '" + table + "' ORDER BY column_name")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        return names;
    }

    @Test
    void v16RefusesToConvertAnExistingCatalogAndSaysWhy() throws SQLException {
        flyway("15").migrate();
        try (Connection connection = open()) {
            insertV15Catalog(connection);
        }

        assertThatThrownBy(() -> flyway("latest").migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("V16 cannot convert the existing trademark catalogs")
                .hasMessageContaining("1 catalog version row(s), 1 entry row(s)")
                .hasMessageContaining("normalization_hash")
                .hasMessageContaining("import the catalogs again");

        try (Connection connection = open()) {
            // the failed migration changed nothing: V15 is still the schema, the data is still there
            assertThat(columns(connection, "trademark_catalog_version")).contains("ruleset_version",
                    "ruleset_content_hash").doesNotContain("normalization_hash");
            assertThat(columns(connection, "trademark_entry")).contains("source_id").doesNotContain("source_ids");
            assertThat(scalar(connection, "SELECT count(*) FROM trademark_entry")).isEqualTo("1");
            assertThat(scalar(connection, "SELECT max(version::int) FROM flyway_schema_history WHERE success"))
                    .isEqualTo("15");
        }
    }

    @Test
    void v16AppliesAfterTheOldCatalogsAreDeleted() throws SQLException {
        flyway("15").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            insertV15Catalog(connection);
            statement.execute("DELETE FROM trademark_entry");
            statement.execute("DELETE FROM trademark_catalog_version");
        }

        // the documented way out: delete the old catalogs (the tables refuse UPDATE, not DELETE), then upgrade
        flyway("latest").migrate();

        try (Connection connection = open()) {
            assertThat(scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE version = '16' AND success"))
                    .as("V16 applied once the old catalogs were gone").isEqualTo("1");
            assertThat(columns(connection, "trademark_catalog_version")).contains("normalization_hash");
        }
    }

    @Test
    void v16OnEmptyTablesGivesTheNewSchema() throws SQLException {
        flyway("15").migrate();

        flyway("latest").migrate();

        try (Connection connection = open()) {
            assertThat(columns(connection, "trademark_catalog_version")).contains("pack_id", "normalization_version",
                    "normalization_hash").doesNotContain("ruleset_version", "ruleset_content_hash");
            assertThat(columns(connection, "trademark_entry")).contains("source_ids").doesNotContain("source_id");
            assertThat(scalar(connection, "SELECT character_maximum_length FROM information_schema.columns "
                    + "WHERE table_name = 'trademark_catalog_version' AND column_name = 'normalization_hash'"))
                    .isEqualTo("64");
            assertThat(scalar(connection, "SELECT is_nullable FROM information_schema.columns "
                    + "WHERE table_name = 'trademark_entry' AND column_name = 'source_ids'")).isEqualTo("NO");
            assertThat(scalar(connection, "SELECT count(*) FROM pg_constraint "
                    + "WHERE conname = 'ck_trademark_entry_source_ids'")).isEqualTo("1");
            long version = insertVersion(connection, "after-16");
            insertEntry(connection, version, "zorvex", "REGISTERED");
        }
    }

    @Test
    void aFreshDatabaseReachesTheSameSchemaAsTheUpgrade() throws SQLException {
        flyway("latest").migrate();
        List<String> fresh;
        try (Connection connection = open()) {
            fresh = new ArrayList<>(columns(connection, "trademark_catalog_version"));
            fresh.addAll(columns(connection, "trademark_entry"));
        }
        flyway("latest").clean();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP EXTENSION IF EXISTS pg_trgm CASCADE");
        }
        flyway("15").migrate();
        flyway("latest").migrate();

        try (Connection connection = open()) {
            List<String> upgraded = new ArrayList<>(columns(connection, "trademark_catalog_version"));
            upgraded.addAll(columns(connection, "trademark_entry"));
            assertThat(upgraded).isEqualTo(fresh);
        }
    }

    // --- V18 (ADR-009, PR A2b): one source row per data file ---

    private static void insertSource(Connection connection, long versionId, String key, String sha) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO trademark_catalog_source (catalog_version_id, source_key, source_name, url, license,
                    source_date, sha256) VALUES (?, ?, 'name', 'url', 'license', current_date, ?)""")) {
            insert.setLong(1, versionId);
            insert.setString(2, key);
            insert.setString(3, sha);
            insert.executeUpdate();
        }
    }

    @Test
    void v18GivesEveryCatalogVersionThatExistedOneSourceRowFromItsSummary() throws SQLException {
        flyway("17").migrate();
        try (Connection connection = open()) {
            insertVersion(connection, "before-18-a");
            insertVersion(connection, "before-18-b");

            flyway("latest").migrate();

            assertThat(scalar(connection, "SELECT count(*) FROM trademark_catalog_source")).isEqualTo("2");
            assertThat(scalar(connection, """
                    SELECT count(*) FROM trademark_catalog_source s JOIN trademark_catalog_version v
                      ON v.id = s.catalog_version_id
                    WHERE s.source_key IS NULL AND s.source_name = v.source_name AND s.url = v.source_reference
                      AND s.license = v.source_license AND s.source_date = v.source_date
                      AND s.sha256 = v.file_sha256""")).isEqualTo("2");
        }
    }

    @Test
    void aSourceKeyIsUsedOncePerVersionAndTheKeylessRowOnceAsWell() throws SQLException {
        flyway("latest").migrate();
        try (Connection connection = open()) {
            long first = insertVersion(connection, "v1");
            long second = insertVersion(connection, "v2");
            String sha = "c".repeat(64);
            insertSource(connection, first, "uspto", sha);
            insertSource(connection, first, "internal", sha);
            insertSource(connection, second, "uspto", sha);
            insertSource(connection, second, null, sha);

            assertThatThrownBy(() -> insertSource(connection, first, "uspto", sha)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_trademark_catalog_source");
            assertThatThrownBy(() -> insertSource(connection, second, null, sha)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_trademark_catalog_source");
        }
    }

    @Test
    void aSourceNeedsAKeyOfTheManifestPatternAndAHexHash() throws SQLException {
        flyway("latest").migrate();
        try (Connection connection = open()) {
            long version = insertVersion(connection, "v1");

            assertThatThrownBy(() -> insertSource(connection, version, "Bad Key", "c".repeat(64)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("source_key_check");
            assertThatThrownBy(() -> insertSource(connection, version, "a:b", "c".repeat(64)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("source_key_check");
            assertThatThrownBy(() -> insertSource(connection, version, "ok", "xyz"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> insertSource(connection, version, "ok", "C".repeat(64)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("sha256_check");
            assertThatThrownBy(() -> insertSource(connection, 987654L, "ok", "c".repeat(64)))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void aSourceRowCannotBeUpdatedAndIsDeletedWithItsVersion() throws SQLException {
        flyway("latest").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            long version = insertVersion(connection, "v1");
            insertSource(connection, version, "uspto", "c".repeat(64));

            assertThatThrownBy(() -> statement.executeUpdate("UPDATE trademark_catalog_source SET license = 'x'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            statement.executeUpdate("DELETE FROM trademark_catalog_version WHERE id = " + version);
            assertThat(scalar(connection, "SELECT count(*) FROM trademark_catalog_source")).isEqualTo("0");
        }
    }

    @Test
    void aFreshDatabaseAndAnUpgradeFrom17HaveTheSameSourceTable() throws SQLException {
        flyway("latest").migrate();
        List<String> fresh;
        try (Connection connection = open()) {
            fresh = new ArrayList<>(columns(connection, "trademark_catalog_source"));
        }
        flyway("latest").clean();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("DROP EXTENSION IF EXISTS pg_trgm CASCADE");
        }
        flyway("17").migrate();
        flyway("latest").migrate();

        try (Connection connection = open()) {
            assertThat(columns(connection, "trademark_catalog_source")).isEqualTo(fresh).isNotEmpty();
        }
    }
}

package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** ADR-010 PR 0b (V8 to V14) makes the pack identity mandatory and replaces the registry and cache identities. */
@Testcontainers
class MigrationPackIdentityContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static Flyway flyway(String target, String location, Callback... callbacks) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations(location).target(target).cleanDisabled(false).callbacks(callbacks)
                // as in application.yml: the default transactional lock would block CREATE INDEX CONCURRENTLY
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load();
    }

    private static Flyway flyway(String target) {
        return flyway(target, "classpath:db/migration");
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @BeforeEach
    void emptyDatabase() {
        flyway("latest").clean();
    }

    private static String scalar(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                query.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    /** Inserts naming every column the way an explicit writer does; a null pack id is an explicit NULL. */
    private static long insertRuleset(Connection connection, String packId, String marketplace, String version)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO ruleset_version (pack_id, marketplace_code, version, content_hash, checked_at, source)
                VALUES (?, ?, ?, 'content-hash', current_date, 'migration-test') RETURNING id
                """)) {
            insert.setString(1, packId);
            insert.setString(2, marketplace);
            insert.setString(3, version);
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static UUID legacyClient(Connection connection) throws SQLException {
        return UUID.fromString(scalar(connection, "SELECT id FROM client WHERE name = 'legacy'"));
    }

    /** A check as the V7 schema allows it: pack id and analysis version may be explicit NULL. */
    private static UUID insertLegacyCheck(Connection connection, String hash, Long rulesetId, String packId,
                                          String analysisVersion) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO listing_check (id, client_id, pack_id, content_hash, marketplace_code, status,
                                           ruleset_version_id, analysis_version)
                VALUES (?, ?, ?, ?, 'ETSY', 'COMPLETED', ?, ?)
                """)) {
            insert.setObject(1, id);
            insert.setObject(2, legacyClient(connection));
            insert.setString(3, packId);
            insert.setString(4, hash);
            insert.setObject(5, rulesetId);
            insert.setString(6, analysisVersion);
            insert.executeUpdate();
        }
        return id;
    }

    private static void insertCheck(Connection connection, UUID clientId, String packId, String hash, long rulesetId,
                                    String analysisVersion) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO listing_check (client_id, pack_id, content_hash, marketplace_code, status,
                                           ruleset_version_id, analysis_version)
                VALUES (?, ?, ?, 'ETSY', 'COMPLETED', ?, ?)
                """)) {
            insert.setObject(1, clientId);
            insert.setString(2, packId);
            insert.setString(3, hash);
            insert.setLong(4, rulesetId);
            insert.setString(5, analysisVersion);
            insert.executeUpdate();
        }
    }

    @Test
    void rowsWrittenAfterV7WithExplicitNullsAreBackfilledWithoutLosingAnyRow() throws Exception {
        flyway("7").migrate();
        try (Connection connection = open()) {
            long etsy = insertRuleset(connection, null, "ETSY", "etsy-2026-11");
            long amazon = insertRuleset(connection, null, "AMAZON", "amazon-2026-11");
            insertRuleset(connection, "listing-rules", "ETSY", "etsy-2026-10");
            UUID explicitNulls = insertLegacyCheck(connection, "hash-1", etsy, null, null);
            UUID oldInstanceRow = insertLegacyCheck(connection, "hash-2", amazon, "listing-rules", null);
            long finding;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO finding (check_id, source, severity, field, message, rule_id, ruleset_version_id)
                    VALUES (?, 'RULE', 'WARNING', 'title', 'message', 'title_length', ?) RETURNING id
                    """)) {
                insert.setObject(1, explicitNulls);
                insert.setLong(2, etsy);
                try (ResultSet rows = insert.executeQuery()) {
                    rows.next();
                    finding = rows.getLong(1);
                }
            }

            flyway("latest").migrate();

            assertThat(scalar(connection, "SELECT count(*) FROM ruleset_version")).isEqualTo("3");
            assertThat(scalar(connection, "SELECT count(*) FROM listing_check")).isEqualTo("2");
            assertThat(scalar(connection, "SELECT count(*) FROM finding")).isEqualTo("1");
            assertThat(scalar(connection, "SELECT check_id FROM finding WHERE id = ?", finding))
                    .isEqualTo(explicitNulls.toString());
            assertThat(scalar(connection, "SELECT count(*) FROM ruleset_version WHERE pack_id <> 'listing-rules'"))
                    .isEqualTo("0");
            assertThat(scalar(connection, "SELECT pack_id FROM listing_check WHERE id = ?", explicitNulls))
                    .isEqualTo("listing-rules");
            assertThat(scalar(connection, "SELECT analysis_version FROM listing_check WHERE id = ?", explicitNulls))
                    .isEqualTo("etsy-2026-11");
            assertThat(scalar(connection, "SELECT analysis_version FROM listing_check WHERE id = ?", oldInstanceRow))
                    .isEqualTo("amazon-2026-11");
            assertThat(scalar(connection, """
                    SELECT count(*) FROM listing_check WHERE pack_id IS NULL OR analysis_version IS NULL
                    """)).isEqualTo("0");
        }
    }

    @Test
    void anInsertThatOmitsThePackIdFailsAfterThe0bContract() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open()) {
            long ruleset = insertRuleset(connection, "listing-rules", "ETSY", "etsy-2026-11");
            UUID client = legacyClient(connection);

            assertThatThrownBy(() -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            INSERT INTO ruleset_version (marketplace_code, version, content_hash, checked_at, source)
                            VALUES ('ETSY', 'omits-pack', 'h', current_date, 's')
                            """);
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("pack_id");

            assertThatThrownBy(() -> {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO listing_check (client_id, content_hash, marketplace_code, status,
                                                   ruleset_version_id, analysis_version)
                        VALUES (?, 'h', 'ETSY', 'COMPLETED', ?, 'etsy-2026-11')
                        """)) {
                    insert.setObject(1, client);
                    insert.setLong(2, ruleset);
                    insert.executeUpdate();
                }
            }).isInstanceOf(SQLException.class).hasMessageContaining("pack_id");

            assertThatThrownBy(() -> insertCheck(connection, client, "listing-rules", "no-analysis", ruleset, null))
                    .isInstanceOf(SQLException.class).hasMessageContaining("analysis_version");
        }
    }

    @Test
    void aCheckWithoutARulesetVersionCannotBeInsertedAfterV14() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open()) {

            assertThatThrownBy(() -> insertLegacyCheck(connection, "no-ruleset", null, "listing-rules", "etsy-2026-11"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ruleset_version_id");
            assertThat(scalar(connection, "SELECT count(*) FROM listing_check")).isEqualTo("0");
        }
    }

    @Test
    void thePackSeparatesRulesetIdentitiesAndTheTripleIsUnique() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open()) {
            insertRuleset(connection, "listing-rules", "ETSY", "shared-version");
            insertRuleset(connection, "other-pack", "ETSY", "shared-version");

            assertThat(scalar(connection, "SELECT count(*) FROM ruleset_version WHERE version = 'shared-version'"))
                    .isEqualTo("2");
            assertThatThrownBy(() -> insertRuleset(connection, "other-pack", "ETSY", "shared-version"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_ruleset_version_pack");
        }
    }

    @Test
    void theCacheIdentityIncludesPackAndAnalysisVersionButNotAcrossClients() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open()) {
            long ruleset = insertRuleset(connection, "listing-rules", "ETSY", "etsy-2026-11");
            UUID client = legacyClient(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO client (name) VALUES ('other')");
            }
            UUID other = UUID.fromString(scalar(connection, "SELECT id FROM client WHERE name = 'other'"));
            insertCheck(connection, client, "listing-rules", "h", ruleset, "etsy-2026-11");

            insertCheck(connection, client, "other-pack", "h", ruleset, "etsy-2026-11");
            insertCheck(connection, client, "listing-rules", "h", ruleset, "composite-2");
            insertCheck(connection, other, "listing-rules", "h", ruleset, "etsy-2026-11");

            assertThatThrownBy(() -> insertCheck(connection, client, "listing-rules", "h", ruleset, "etsy-2026-11"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_listing_check_completed_identity");
        }
    }

    @Test
    void theOldIdentitiesAreGoneAndTheNewIndexesAreValid() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = open()) {
            assertThat(scalar(connection, "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_ruleset_version'"))
                    .isEqualTo("0");
            assertThat(scalar(connection, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_listing_check_completed'"))
                    .isEqualTo("0");
            assertThat(scalar(connection, "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_ruleset_version_pack'"))
                    .isEqualTo("1");
            assertThat(scalar(connection, """
                    SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                    WHERE c.relname IN ('uq_ruleset_version_pack', 'uq_listing_check_completed_identity')
                      AND i.indisvalid AND i.indisunique
                    """)).isEqualTo("2");
            assertThat(scalar(connection, "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_ruleset_version_pack'"))
                    .contains("(pack_id, marketplace_code, version)");
            assertThat(scalar(connection,
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_listing_check_completed_identity'"))
                    .contains("(client_id, marketplace_code, pack_id, content_hash, ruleset_version_id, analysis_version)")
                    .contains("COMPLETED");
        }
    }

    @Test
    void aCheckWithoutARulesetVersionStopsTheMigrationAndChangesNothing() throws Exception {
        flyway("7").migrate();
        long ruleset;
        try (Connection connection = open()) {
            ruleset = insertRuleset(connection, "listing-rules", "ETSY", "etsy-2026-11");
            insertLegacyCheck(connection, "with-ruleset", ruleset, "listing-rules", null);
            insertLegacyCheck(connection, "without-ruleset", null, "listing-rules", null);
        }

        assertThatThrownBy(() -> flyway("latest").migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("1 listing_check row(s) have ruleset_version_id NULL")
                .hasMessageContaining("docs/deployment.md");

        try (Connection connection = open()) {
            assertThat(scalar(connection, "SELECT count(*) FROM listing_check")).isEqualTo("2");
            assertThat(scalar(connection, "SELECT count(*) FROM listing_check WHERE analysis_version IS NULL"))
                    .isEqualTo("2");
            assertThat(scalar(connection, """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_name = 'listing_check' AND column_name = 'pack_id'
                    """)).isEqualTo("YES");
            assertThat(flyway("latest").info().current().getVersion().getVersion()).isEqualTo("7");

            // the operator assigns the ruleset the check was produced with; the migration then goes through
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE listing_check SET ruleset_version_id = " + ruleset
                        + " WHERE ruleset_version_id IS NULL");
            }
        }
        flyway("latest").migrate();
        try (Connection connection = open()) {
            assertThat(scalar(connection, "SELECT count(*) FROM listing_check WHERE analysis_version = 'etsy-2026-11'"))
                    .isEqualTo("2");
        }
    }

    @Test
    void theRegistryConstraintIsNotSwappedWhileItsReplacementIsInvalid() throws Exception {
        flyway("9").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'uq_ruleset_version_pack'::regclass");

            assertThatThrownBy(() -> flyway("latest").migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("uq_ruleset_version_pack is missing or invalid");

            assertThat(scalar(connection, "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_ruleset_version'"))
                    .isEqualTo("1");
        }
    }

    @Test
    void theOldCacheIndexIsNotDroppedWhileItsReplacementIsInvalid() throws Exception {
        flyway("11").migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE pg_index SET indisvalid = false "
                    + "WHERE indexrelid = 'uq_listing_check_completed_identity'::regclass");

            assertThatThrownBy(() -> flyway("latest").migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("uq_listing_check_completed_identity is missing or invalid");

            assertThat(scalar(connection, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_listing_check_completed'"))
                    .isEqualTo("1");
        }
    }

    /** Records, per migration version, whether Flyway ran it outside a transaction (autocommit on). */
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

    @Test
    void theConcurrentIndexMigrationsRunOutsideATransactionAndTheOthersInsideOne() {
        TransactionRecorder recorder = new TransactionRecorder();

        flyway("latest", "classpath:db/migration", recorder).migrate();

        assertThat(recorder.outsideTransaction).containsEntry("8", false).containsEntry("9", true)
                .containsEntry("10", false).containsEntry("11", true).containsEntry("12", false)
                .containsEntry("13", true).containsEntry("14", false);
        for (String script : new String[] {"V9__ruleset_version_pack_unique_index",
                "V11__listing_check_identity_index", "V13__drop_listing_check_old_cache_index"}) {
            assertThat(scriptText("db/migration/" + script + ".sql.conf")).contains("executeInTransaction=false");
            assertThat(scriptText("db/migration/" + script + ".sql")).contains("CONCURRENTLY");
        }
        for (String script : new String[] {"V8__pack_identity_contract_verify",
                "V10__ruleset_version_pack_unique_constraint", "V12__listing_check_identity_index_verify",
                "V14__listing_check_ruleset_version_not_null"}) {
            assertThat(scriptText("db/migration/" + script + ".sql")).doesNotContain("CONCURRENTLY");
        }
    }

    @Test
    void aScriptConfigFileTakesAMigrationOutOfItsTransaction(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__plain.sql"), "SELECT 1;", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("V2__configured.sql"), "SELECT 1;", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("V2__configured.sql.conf"), "executeInTransaction=false\n",
                StandardCharsets.UTF_8);
        TransactionRecorder recorder = new TransactionRecorder();

        flyway("latest", "filesystem:" + directory, recorder).migrate();

        assertThat(recorder.outsideTransaction).containsEntry("1", false).containsEntry("2", true);
    }

    private static String scriptText(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }
}

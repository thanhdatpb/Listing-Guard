package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** PR 0a (V7) adds pack identity while old instances may still write the V6 schema; PR 0b is in {@link MigrationPackIdentityContractTest}. */
@Testcontainers
class MigrationPackIdentityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    private static long insertRuleset(Connection connection, String version) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO ruleset_version (marketplace_code, version, content_hash, checked_at, source)
                VALUES ('ETSY', ?, 'content-hash', current_date, 'migration-test') RETURNING id
                """)) {
            insert.setString(1, version);
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static UUID insertCheck(Connection connection, UUID id, String hash, long rulesetId,
                                    UUID clientId) throws SQLException {
        String sql = clientId == null
                ? """
                  INSERT INTO listing_check (id, content_hash, marketplace_code, status, ruleset_version_id)
                  VALUES (?, ?, 'ETSY', 'COMPLETED', ?) RETURNING id
                  """
                : """
                  INSERT INTO listing_check (id, client_id, content_hash, marketplace_code, status, ruleset_version_id)
                  VALUES (?, ?, ?, 'ETSY', 'COMPLETED', ?) RETURNING id
                  """;
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setObject(1, id);
            if (clientId == null) {
                insert.setString(2, hash);
                insert.setLong(3, rulesetId);
            } else {
                insert.setObject(2, clientId);
                insert.setString(3, hash);
                insert.setLong(4, rulesetId);
            }
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getObject(1, UUID.class);
            }
        }
    }

    private static long insertFinding(Connection connection, UUID checkId, long rulesetId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO finding (check_id, source, severity, field, message, suggestion, rule_id,
                                     ruleset_version_id, value)
                VALUES (?, 'RULE', 'WARNING', 'tags[0]', 'legacy message', 'legacy suggestion',
                        'duplicate_tags', ?, 'mug') RETURNING id
                """)) {
            insert.setObject(1, checkId);
            insert.setLong(2, rulesetId);
            try (ResultSet rows = insert.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
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

    @Test
    void v7BackfillsExistingRowsAndAllowsV6WritersToOmitNewColumns() throws Exception {
        flyway("5").migrate();
        UUID checkBeforeClientMigration = UUID.randomUUID();
        String hashBeforeClientMigration = "pre-v6-" + UUID.randomUUID();
        long rulesetBeforeClientMigration;
        long findingBeforeClientMigration;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            rulesetBeforeClientMigration = insertRuleset(connection, "pre-v6-version");
            insertCheck(connection, checkBeforeClientMigration, hashBeforeClientMigration,
                    rulesetBeforeClientMigration, null);
            findingBeforeClientMigration = insertFinding(connection, checkBeforeClientMigration,
                    rulesetBeforeClientMigration);
        }

        flyway("6").migrate();
        UUID checkFromV6Writer = UUID.randomUUID();
        String hashFromV6Writer = "v6-" + UUID.randomUUID();
        long rulesetFromV6Writer;
        UUID legacyClientId;
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            rulesetFromV6Writer = insertRuleset(connection, "v6-version");
            legacyClientId = UUID.fromString(scalar(connection, "SELECT id FROM client WHERE name = 'legacy'"));
            insertCheck(connection, checkFromV6Writer, hashFromV6Writer, rulesetFromV6Writer, legacyClientId);
        }

        flyway("7").migrate();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(scalar(connection, "SELECT count(*) FROM listing_check")).isEqualTo("2");
            assertThat(scalar(connection, "SELECT count(*) FROM ruleset_version")).isEqualTo("2");
            assertThat(scalar(connection, "SELECT count(*) FROM finding")).isEqualTo("1");
            assertThat(scalar(connection, "SELECT pack_id FROM ruleset_version WHERE id = ?", rulesetBeforeClientMigration))
                    .isEqualTo("listing-rules");
            assertThat(scalar(connection, "SELECT pack_id FROM listing_check WHERE id = ?", checkBeforeClientMigration))
                    .isEqualTo("listing-rules");
            assertThat(scalar(connection, "SELECT analysis_version FROM listing_check WHERE id = ?",
                    checkBeforeClientMigration)).isEqualTo("pre-v6-version");
            assertThat(scalar(connection, "SELECT analysis_version FROM listing_check WHERE id = ?",
                    checkFromV6Writer)).isEqualTo("v6-version");
            assertThat(scalar(connection, "SELECT client_id FROM listing_check WHERE id = ?", checkFromV6Writer))
                    .isEqualTo(legacyClientId.toString());
            assertThat(scalar(connection, "SELECT id FROM finding WHERE check_id = ?", checkBeforeClientMigration))
                    .isEqualTo(Long.toString(findingBeforeClientMigration));
            assertThat(scalar(connection, "SELECT value FROM finding WHERE id = ?", findingBeforeClientMigration))
                    .isEqualTo("mug");

            long legacyRulesetId = insertRuleset(connection, "post-v7-legacy-write");
            UUID postMigrationOldWriterCheck = UUID.randomUUID();
            insertCheck(connection, postMigrationOldWriterCheck, "post-v7-" + UUID.randomUUID(),
                    legacyRulesetId, legacyClientId);

            assertThat(scalar(connection, "SELECT pack_id FROM listing_check WHERE id = ?", postMigrationOldWriterCheck))
                    .isEqualTo("listing-rules");
            assertThat(scalar(connection, "SELECT analysis_version FROM listing_check WHERE id = ?",
                    postMigrationOldWriterCheck)).isNull();
            assertThat(scalar(connection, "SELECT pack_id FROM ruleset_version WHERE id = ?", legacyRulesetId))
                    .isEqualTo("listing-rules");
            assertThat(scalar(connection, """
                    SELECT conname FROM pg_constraint WHERE conname = 'uq_ruleset_version'
                    """)).isEqualTo("uq_ruleset_version");
            assertThat(scalar(connection, "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_listing_check_completed'"))
                    .contains("client_id", "marketplace_code", "content_hash", "ruleset_version_id");

            assertThatThrownBy(() -> insertRuleset(connection, "pre-v6-version"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_ruleset_version");

            UUID duplicate = UUID.randomUUID();
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO listing_check (id, client_id, content_hash, marketplace_code, status, ruleset_version_id)
                    VALUES (?, ?, ?, 'ETSY', 'COMPLETED', ?)
                    """)) {
                insert.setObject(1, duplicate);
                insert.setObject(2, legacyClientId);
                insert.setString(3, "post-v7-" + UUID.randomUUID());
                insert.setLong(4, legacyRulesetId);
                insert.executeUpdate();
            }
            String duplicateHash = scalar(connection, "SELECT content_hash FROM listing_check WHERE id = ?", duplicate);
            assertThatThrownBy(() -> insertCheck(connection, UUID.randomUUID(), duplicateHash, legacyRulesetId, legacyClientId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uq_listing_check_completed");
        }
    }
}

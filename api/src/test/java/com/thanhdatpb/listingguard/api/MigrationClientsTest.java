package com.thanhdatpb.listingguard.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
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

/** V6 moves the checks that already exist to the inactive client {@code legacy} and makes the cache key per client. */
@Testcontainers
class MigrationClientsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    @Test
    void existingChecksBelongToTheInactiveLegacyClientWithNoKeys() throws Exception {
        flyway("5").migrate();
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO listing_check (content_hash, marketplace_code, status) VALUES ('" + hash + "', 'ETSY', 'COMPLETED')");

            flyway("latest").migrate();

            assertThat(scalar(statement, "SELECT active FROM client WHERE name = 'legacy'")).isEqualTo("f");
            assertThat(scalar(statement, "SELECT count(*) FROM api_key")).isEqualTo("0");
            assertThat(scalar(statement, "SELECT c.name FROM listing_check l JOIN client c ON c.id = l.client_id WHERE l.content_hash = '" + hash + "'"))
                    .isEqualTo("legacy");
        }
    }

    @Test
    void theCompletedCheckIsUniquePerClientNotAcrossClients() throws Exception {
        flyway("latest").migrate();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO client (name) VALUES ('uniq-a'), ('uniq-b')");
            statement.executeUpdate("INSERT INTO ruleset_version (marketplace_code, version) VALUES ('ETSY', 'uniq-test')");
            String rulesetId = scalar(statement, "SELECT id FROM ruleset_version WHERE version = 'uniq-test'");
            String insert = "INSERT INTO listing_check (client_id, content_hash, marketplace_code, status, ruleset_version_id) "
                    + "SELECT id, 'same-hash', 'ETSY', 'COMPLETED', " + rulesetId + " FROM client WHERE name = '%s'";

            statement.executeUpdate(insert.formatted("uniq-a"));
            statement.executeUpdate(insert.formatted("uniq-b"));

            assertThatThrownBy(() -> statement.executeUpdate(insert.formatted("uniq-a")))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_listing_check_completed");
        }
    }
}

package com.thanhdatpb.listingguard.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MigrationReviewCasesTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradePreservesExistingClientsAndKeysWithoutGrantingWrites() {
        var before = flyway("42");
        before.migrate();
        var jdbc = new JdbcTemplate(before.getConfiguration().getDataSource());
        UUID client = jdbc.queryForObject("INSERT INTO client (name) VALUES ('old-review-client') RETURNING id", UUID.class);
        jdbc.update("INSERT INTO api_key (client_id, key_id, key_hash) VALUES (?, 'old-key', ?)", client, "a".repeat(64));

        flyway("43").migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_key WHERE client_id = ?", Integer.class, client)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_case_writer", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_case", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO review_case_writer (api_key_id) VALUES (?)", UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target)
                .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load();
    }
}

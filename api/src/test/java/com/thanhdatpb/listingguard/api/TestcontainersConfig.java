package com.thanhdatpb.listingguard.api;

import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One pgvector Postgres for every integration test that imports this. Tests with the same context
 * configuration share the cached Spring context, so the container starts once per test run.
 *
 * <p>Opt-in without Docker: when {@code LG_TEST_JDBC_URL} has a value (with {@code LG_TEST_JDBC_USER} and
 * {@code LG_TEST_JDBC_PASSWORD}) no container starts and every test that imports this configuration uses that
 * database, whatever its profiles ({@code scripts/test-db-local.sh} makes one; see docs/testing.md). The database
 * needs the pgvector extension. Without the variable the behaviour is the one above, unchanged (CI).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    static final String JDBC_URL = "LG_TEST_JDBC_URL";
    static final String JDBC_USER = "LG_TEST_JDBC_USER";
    static final String JDBC_PASSWORD = "LG_TEST_JDBC_PASSWORD";

    /** True when {@code LG_TEST_JDBC_URL} is set to a non-blank value. */
    static boolean externalDatabase(Environment environment) {
        String url = environment.getProperty(JDBC_URL);
        return url != null && !url.isBlank();
    }

    static final class ExternalDatabase implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return externalDatabase(context.getEnvironment());
        }
    }

    static final class NoExternalDatabase implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !externalDatabase(context.getEnvironment());
        }
    }

    @Bean
    @ServiceConnection
    @Conditional(NoExternalDatabase.class)
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    /**
     * The database of {@code LG_TEST_JDBC_URL}, given as connection details like the container's {@code @ServiceConnection}: the data source,
     * Flyway and JPA take it before any property, whatever the profiles (dev, test, admin...) say. A {@code DynamicPropertyRegistrar} would not do:
     * in a context with a web server (RANDOM_PORT) Tomcat builds the filters, and so the data source, before that registrar has run.
     */
    @Bean
    @Conditional(ExternalDatabase.class)
    JdbcConnectionDetails externalDatabaseConnection(Environment environment) {
        String url = environment.getProperty(JDBC_URL);
        String user = environment.getProperty(JDBC_USER, "listingguard");
        String password = environment.getProperty(JDBC_PASSWORD, "listingguard");
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return url;
            }

            @Override
            public String getUsername() {
                return user;
            }

            @Override
            public String getPassword() {
                return password;
            }
        };
    }

    /**
     * Test-wide properties, set here so every test importing this configuration keeps the same context:
     * the deterministic fake embedding provider (no Ollama needed) and the debug search endpoint.
     */
    @Bean
    DynamicPropertyRegistrar testProperties(Environment environment) {
        return registry -> {
            // a test that proves the default configuration (provider none, no key) sets test.default-embedding-provider=true
            if (!environment.getProperty("test.default-embedding-provider", Boolean.class, false)) {
                registry.add("listing-guard.embedding.provider", () -> "hashing");
            }
            registry.add("listing-guard.policies.search-endpoint.enabled", () -> "true");
            // no cache, so a test that revokes a key or deactivates a client sees it at once
            registry.add("listing-guard.security.auth-cache-ttl", () -> "0s");
            // no marketplace is planned in production; the tests need one to exercise the 422 "not yet supported" answer
            registry.add("listing-guard.marketplaces.planned", () -> "WALMART");
            // the scheduled trademark cleanup stays off so it cannot delete rows under a test; a test of the schedule itself
            // sets test.scheduled-cleanup=true and its own listing-guard.trademark.retention.* values
            if (!environment.getProperty("test.scheduled-cleanup", Boolean.class, false)) {
                registry.add("listing-guard.trademark.retention.cleanup-enabled", () -> "false");
            }
            // generous by default so tests never trip the limit; a test that exercises it sets test.custom-rate-limit=true
            // and its own listing-guard.rate-limit.* values
            if (!environment.getProperty("test.custom-rate-limit", Boolean.class, false)) {
                registry.add("listing-guard.rate-limit.burst", () -> "100000");
                registry.add("listing-guard.rate-limit.requests-per-minute", () -> "6000000");
            }
        };
    }

    /** The clients and keys of {@link ApiTestKeys}, registered once the schema exists. */
    @Bean
    ApplicationRunner testClients(ClientProvisioning provisioning) {
        return args -> {
            provisioning.ensureKnownKey(ApiTestKeys.CLIENT_1, ApiTestKeys.KEY_1, "test key 1");
            provisioning.ensureKnownKey(ApiTestKeys.CLIENT_1, ApiTestKeys.KEY_1_SECOND, "test key 1, second");
            provisioning.ensureKnownKey(ApiTestKeys.CLIENT_2, ApiTestKeys.KEY_2, "test key 2");
        };
    }
}

package com.thanhdatpb.listingguard.api;

import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One pgvector Postgres for every integration test that imports this. Tests with the same context
 * configuration share the cached Spring context, so the container starts once per test run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    /**
     * Test-wide properties, set here so every test importing this configuration keeps the same context:
     * the deterministic fake embedding provider (no Ollama needed) and the debug search endpoint.
     */
    @Bean
    DynamicPropertyRegistrar testProperties(Environment environment) {
        return registry -> {
            registry.add("listing-guard.embedding.provider", () -> "hashing");
            registry.add("listing-guard.policies.search-endpoint.enabled", () -> "true");
            // no cache, so a test that revokes a key or deactivates a client sees it at once
            registry.add("listing-guard.security.auth-cache-ttl", () -> "0s");
            // no marketplace is planned in production; the tests need one to exercise the 422 "not yet supported" answer
            registry.add("listing-guard.marketplaces.planned", () -> "WALMART");
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

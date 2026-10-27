package com.thanhdatpb.listingguard.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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
    DynamicPropertyRegistrar testProperties() {
        return registry -> {
            registry.add("listing-guard.embedding.provider", () -> "hashing");
            registry.add("listing-guard.policies.search-endpoint.enabled", () -> "true");
        };
    }
}

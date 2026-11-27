package com.thanhdatpb.listingguard.api.demo;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A workspace is determined by its database marker, never by a request or a client name. */
@Component
public class DemoWorkspaceRegistry {
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public DemoWorkspaceRegistry(JdbcTemplate jdbc, @Value("${listing-guard.demo.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<Long> catalogId(UUID client) {
        return jdbc.query("SELECT catalog_version_id FROM demo_workspace WHERE client_id = ?",
                (rs, index) -> rs.getLong(1), client).stream().findFirst();
    }

    public boolean marked(UUID client) {
        return catalogId(client).isPresent();
    }
}

package com.thanhdatpb.listingguard.api.web;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Up only if a query to PostgreSQL succeeds; the query and the wait for a connection are both short. */
@Component
public class DatabaseHealthProbe implements HealthProbe {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthProbe.class);
    private static final int QUERY_TIMEOUT_SECONDS = 2;

    private final JdbcTemplate jdbc;

    public DatabaseHealthProbe(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    }

    @Override
    public boolean isUp() {
        try {
            return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException e) {
            log.warn("Health check: database is not reachable: {}", e.getMessage());
            return false;
        }
    }
}

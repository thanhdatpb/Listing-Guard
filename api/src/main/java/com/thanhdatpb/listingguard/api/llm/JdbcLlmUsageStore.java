package com.thanhdatpb.listingguard.api.llm;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link LlmUsageStore} on table {@code llm_usage} (V40). One statement reserves a request, so two instances share the limit. */
public class JdbcLlmUsageStore implements LlmUsageStore {

    private final JdbcTemplate jdbc;

    public JdbcLlmUsageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean reserve(String providerId, LocalDate day, int limit) {
        return jdbc.update("""
                INSERT INTO llm_usage (usage_day, provider_id, requests) VALUES (?, ?, 1)
                ON CONFLICT (usage_day, provider_id) DO UPDATE SET requests = llm_usage.requests + 1
                WHERE llm_usage.requests < ?
                """, java.sql.Date.valueOf(day), providerId, limit) == 1;
    }

    @Override
    public void addTokens(String providerId, LocalDate day, long inputTokens, long outputTokens) {
        jdbc.update("UPDATE llm_usage SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ? "
                + "WHERE usage_day = ? AND provider_id = ?", inputTokens, outputTokens, java.sql.Date.valueOf(day), providerId);
    }

    @Override
    public Map<String, Long> requestsOn(LocalDate day) {
        Map<String, Long> requests = new HashMap<>();
        jdbc.query("SELECT provider_id, requests FROM llm_usage WHERE usage_day = ?",
                rs -> {
                    requests.put(rs.getString("provider_id"), rs.getLong("requests"));
                }, java.sql.Date.valueOf(day));
        return requests;
    }
}

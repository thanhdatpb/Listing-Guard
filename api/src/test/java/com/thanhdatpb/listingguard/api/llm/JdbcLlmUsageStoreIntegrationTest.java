package com.thanhdatpb.listingguard.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The daily counter on the real table (V41): one statement takes a request, so concurrent callers never pass the limit. */
@SpringBootTest
@Import(TestcontainersConfig.class)
class JdbcLlmUsageStoreIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reservesUpToTheLimitAndNoFurtherEvenWhenCallersRace() throws Exception {
        JdbcLlmUsageStore store = new JdbcLlmUsageStore(jdbc);
        String provider = "it-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDate day = LocalDate.of(2026, 11, 16);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger granted = new AtomicInteger();
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            done.add(pool.submit(() -> {
                if (store.reserve(provider, day, 10)) {
                    granted.incrementAndGet();
                }
            }));
        }
        for (Future<?> future : done) {
            future.get();
        }
        pool.shutdown();

        assertThat(granted.get()).isEqualTo(10);
        assertThat(store.requestsOn(day)).containsEntry(provider, 10L);
        assertThat(store.reserve(provider, day.plusDays(1), 10)).as("a new day starts at zero").isTrue();
    }

    @Test
    void keepsTokenCountsPerDayAndProvider() {
        JdbcLlmUsageStore store = new JdbcLlmUsageStore(jdbc);
        String provider = "it-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDate day = LocalDate.of(2026, 11, 17);
        store.reserve(provider, day, 5);
        store.addTokens(provider, day, 120, 30);
        store.addTokens(provider, day, 80, 10);

        assertThat(jdbc.queryForObject("SELECT input_tokens FROM llm_usage WHERE provider_id = ? AND usage_day = ?", Long.class,
                provider, java.sql.Date.valueOf(day))).isEqualTo(200L);
        assertThat(jdbc.queryForObject("SELECT output_tokens FROM llm_usage WHERE provider_id = ? AND usage_day = ?", Long.class,
                provider, java.sql.Date.valueOf(day))).isEqualTo(40L);
    }
}

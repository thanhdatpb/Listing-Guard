package com.thanhdatpb.listingguard.api.phrase;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"listing-guard.phrase-screening.enabled=true", "listing-guard.phrase-screening.max-phrases-per-day=3",
        "listing-guard.plans.enabled=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PhraseScreeningApiIntegrationTest {
    private static final String PATH = "/api/v1/phrase-screenings";
    private static final String WATCHLIST = """
            {"entries":[{"mark":"Brimwick Otters","aliases":["Bkwott"],"serialNumber":"12345678"}]}
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClientProvisioning provisioning;
    @Autowired JdbcTemplate jdbc;

    @Test
    void watchlistIsTenantScopedAndOnlyCountersAreStored() throws Exception {
        String caller = key();
        String other = key();
        String privateSample = "SYNTHETIC-BUYER-DO-NOT-RETAIN";
        String request = mapper.writeValueAsString(Map.of("phrases",
                List.of(Map.of("text", "BKWoTT"), Map.of("text", privateSample), Map.of("text", " "))));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isUnauthorized());
        screen(other, request).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("trademark-not-configured"));
        watch(caller);
        mvc.perform(get("/api/v1/capabilities").header(HEADER, caller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.phraseScreening.enabled").value(true));
        screen(caller, request).andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogStatus").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.completeness").value("INCOMPLETE"))
                .andExpect(jsonPath("$.summary.block").value(1))
                .andExpect(jsonPath("$.summary.clear").value(1))
                .andExpect(jsonPath("$.summary.dropped").value(1))
                .andExpect(jsonPath("$.phrases[0].verdict").value("BLOCK"))
                .andExpect(jsonPath("$.phrases[0].findings[0].source").value("WATCHLIST"))
                .andExpect(jsonPath("$.phrases[0].findings[0].matchedAlias").value("Bkwott"))
                .andExpect(jsonPath("$.phrases[0].findings[0].sourceIds[0]").value("uspto:12345678"));
        screen(other, request).andExpect(status().isServiceUnavailable());
        screen(caller, request).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("phrase-daily-limit"));

        var rows = jdbc.queryForList("SELECT to_jsonb(run)::text FROM phrase_screening_run run", String.class);
        assertThat(rows).anySatisfy(row -> assertThat(row).contains("\"phrase_count\": 3", "\"block_count\": 1"))
                .allSatisfy(row -> assertThat(row).doesNotContain(privateSample, "BKWoTT", caller, other));
        mvc.perform(get("/api/v1/usage").header(HEADER, caller)).andExpect(status().isOk())
                .andExpect(jsonPath("$.used").value(1));
    }

    @Test
    void rejectsAnOversizedArrayAndInvalidVolumes() throws Exception {
        String caller = key();
        String oversized = mapper.writeValueAsString(Map.of("phrases", java.util.Collections.nCopies(2001, Map.of("text", "a"))));
        screen(caller, oversized).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Send between 1 and 2000 phrases per request."));
        screen(caller, """
                {"phrases":[{"text":"toy","searchVolume":-1}]}
                """).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid-request"));
    }

    @Test
    void aFullLengthTwoThousandPhraseRequestPassesTheBodyFilter() throws Exception {
        String caller = key();
        String request = mapper.writeValueAsString(Map.of("phrases",
                java.util.Collections.nCopies(2000, Map.of("text", "a".repeat(200)))));
        assertThat(request.length()).isGreaterThan(262144);
        screen(caller, request).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("phrase-daily-limit"));
    }

    @Test
    void concurrentRunsCannotTakeTheSameRemainingDailyBudget() throws Exception {
        String caller = key();
        watch(caller);
        Callable<Integer> run = () -> screen(caller, """
                {"phrases":[{"text":"Bkwott"},{"text":"simple toy"}]}
                """).andReturn().getResponse().getStatus();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var statuses = executor.invokeAll(List.of(run, run));
            assertThat(List.of(statuses.get(0).get(), statuses.get(1).get())).containsExactlyInAnyOrder(200, 429);
        }
    }

    private String key() {
        String name = "phrase-" + UUID.randomUUID().toString().substring(0, 16);
        provisioning.createClient(name);
        jdbc.update("UPDATE client SET plan_code = 'SELLER' WHERE name = ?", name);
        return provisioning.createKey(name, "phrase test").secret();
    }

    private void watch(String caller) throws Exception {
        mvc.perform(put("/api/v1/trademark-watchlist").header(HEADER, caller).contentType(MediaType.APPLICATION_JSON)
                .content(WATCHLIST)).andExpect(status().isOk());
    }

    private ResultActions screen(String caller, String body) throws Exception {
        return mvc.perform(post(PATH).header(HEADER, caller).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}

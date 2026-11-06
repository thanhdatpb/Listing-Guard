package com.thanhdatpb.listingguard.api.variation;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.amazonFamily;
import static com.thanhdatpb.listingguard.api.variation.VariationApiSupport.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRetentionCleanup;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled cleanup of variation results (ADR-015, ADR-010): it deletes expired checks with their findings, leaves the
 * others, and takes the retention advisory lock, so a run that cannot take it is skipped instead of waiting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class VariationRetentionCleanupIntegrationTest {

    @Autowired
    VariationRetentionCleanup cleanup;

    @Autowired
    VariationRulesets rulesets;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    /** A new check with one finding; returns its id. */
    private String newCheck() throws Exception {
        String u = unique();
        String response = mvc.perform(post("/api/v1/variation-checks").header(HEADER, KEY_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(amazonFamily(rulesets.ruleset(), u, "OTHER-" + u))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(response).get("checkId").asText();
    }

    private void expire(String id) {
        jdbc.update("UPDATE variation_check SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day' "
                + "WHERE id = ?::uuid", id);
    }

    private int checks(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM variation_check WHERE id = ?::uuid", Integer.class, id);
    }

    private int findings(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM variation_check_finding WHERE check_id = ?::uuid", Integer.class, id);
    }

    @Test
    void expiredChecksAreDeletedWithTheirFindingsAndTheOthersStay() throws Exception {
        String expired1 = newCheck();
        String expired2 = newCheck();
        String live = newCheck();
        expire(expired1);
        expire(expired2);
        assertThat(findings(expired1)).isEqualTo(1);

        VariationRetentionCleanup.Outcome outcome = cleanup.runOnce();

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.deletedChecks()).isGreaterThanOrEqualTo(2);
        assertThat(checks(expired1)).isZero();
        assertThat(checks(expired2)).isZero();
        assertThat(findings(expired1)).as("the findings go with their check").isZero();
        assertThat(checks(live)).isEqualTo(1);
        assertThat(findings(live)).isEqualTo(1);
    }

    @Test
    void whenNothingIsExpiredNothingIsDeleted() throws Exception {
        String live = newCheck();
        cleanup.runOnce();

        VariationRetentionCleanup.Outcome outcome = cleanup.runOnce();

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.deletedChecks()).isZero();
        assertThat(checks(live)).isEqualTo(1);
    }

    @Test
    void aRunThatCannotTakeTheRetentionLockIsSkippedNotWaitedFor() throws Exception {
        String expired = newCheck();
        expire(expired);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            VariationRetentionCleanup.Outcome whileLocked = transactions.execute(status -> {
                // another transaction holds the lock reserved for retention cleanup, as the trademark cleanup would
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", Object.class,
                        TrademarkRetentionCleanup.LOCK_KEY_1, TrademarkRetentionCleanup.LOCK_KEY_2);
                try {
                    return other.submit(cleanup::runOnce).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(whileLocked.skipped()).isTrue();
            assertThat(whileLocked.deletedChecks()).isZero();
            assertThat(checks(expired)).as("nothing was deleted while the lock was elsewhere").isEqualTo(1);

            assertThat(cleanup.runOnce().skipped()).isFalse();
            assertThat(checks(expired)).isZero();
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    void theLockKeysAreTheOnesTheTrademarkCleanupUses() {
        // ADR-010 reserves one pair for retention cleanup; this job shares it and does not own a copy of the numbers
        assertThat(TrademarkRetentionCleanup.LOCK_KEY_1).isEqualTo(1279746628);
        assertThat(TrademarkRetentionCleanup.LOCK_KEY_2).isEqualTo(1380271182);
    }
}

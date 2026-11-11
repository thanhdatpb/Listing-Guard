package com.thanhdatpb.listingguard.api.personalization;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.body;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.emoji;
import static com.thanhdatpb.listingguard.api.personalization.PersonalizationApiSupport.unique;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled cleanup of personalization results (ADR-016, ADR-010): it deletes expired screenings with their findings,
 * leaves the others, and takes the shared retention advisory lock, so a run that cannot take it is skipped instead of waiting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class PersonalizationRetentionCleanupIntegrationTest {

    @Autowired
    PersonalizationRetentionCleanup cleanup;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    /** A new screening with one finding (the emoji); returns its id. Without a catalog it is INCOMPLETE, which is stored too. */
    private String newScreening() throws Exception {
        String response = mvc.perform(post("/api/v1/personalization-screenings").header(HEADER, KEY_1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(body("ETSY", null, "name", unique() + emoji()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(response).get("checkId").asText();
    }

    private void expire(String id) {
        jdbc.update("UPDATE personalization_screening SET created_at = now() - interval '2 days', "
                + "expires_at = now() - interval '1 day' WHERE id = ?::uuid", id);
    }

    private int screenings(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM personalization_screening WHERE id = ?::uuid", Integer.class, id);
    }

    private int findings(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM personalization_screening_finding WHERE check_id = ?::uuid",
                Integer.class, id);
    }

    @Test
    void expiredScreeningsAreDeletedWithTheirFindingsAndTheOthersStay() throws Exception {
        String expired1 = newScreening();
        String expired2 = newScreening();
        String live = newScreening();
        expire(expired1);
        expire(expired2);
        assertThat(findings(expired1)).isEqualTo(1);

        PersonalizationRetentionCleanup.Outcome outcome = cleanup.runOnce();

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.deletedChecks()).isGreaterThanOrEqualTo(2);
        assertThat(screenings(expired1)).isZero();
        assertThat(screenings(expired2)).isZero();
        assertThat(findings(expired1)).as("the findings go with their screening").isZero();
        assertThat(screenings(live)).isEqualTo(1);
        assertThat(findings(live)).isEqualTo(1);
    }

    @Test
    void whenNothingIsExpiredNothingIsDeleted() throws Exception {
        String live = newScreening();
        cleanup.runOnce();

        PersonalizationRetentionCleanup.Outcome outcome = cleanup.runOnce();

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.deletedChecks()).isZero();
        assertThat(screenings(live)).isEqualTo(1);
    }

    @Test
    void aRunThatCannotTakeTheRetentionLockIsSkippedNotWaitedFor() throws Exception {
        String expired = newScreening();
        expire(expired);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            PersonalizationRetentionCleanup.Outcome whileLocked = transactions.execute(status -> {
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
            assertThat(screenings(expired)).as("nothing was deleted while the lock was elsewhere").isEqualTo(1);

            assertThat(cleanup.runOnce().skipped()).isFalse();
            assertThat(screenings(expired)).isZero();
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    void theLockKeysAreTheOnesThatAdr010ReservesForRetentionCleanup() {
        // one pair for every retention cleanup; this job shares it and does not own a copy of the numbers
        assertThat(TrademarkRetentionCleanup.LOCK_KEY_1).isEqualTo(1279746628);
        assertThat(TrademarkRetentionCleanup.LOCK_KEY_2).isEqualTo(1380271182);
    }
}

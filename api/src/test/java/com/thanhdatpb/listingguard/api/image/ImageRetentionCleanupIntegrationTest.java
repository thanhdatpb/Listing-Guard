package com.thanhdatpb.listingguard.api.image;

import static com.thanhdatpb.listingguard.api.ApiTestKeys.HEADER;
import static com.thanhdatpb.listingguard.api.ApiTestKeys.KEY_1;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.CHECKS;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.MAPPER;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.additional;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.main;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.notAnImage;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.product;
import static com.thanhdatpb.listingguard.api.image.ImageApiSupport.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.image.ImageApiSupport.Part;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRetentionCleanup;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention of image checks (ADR-008 "Expiry and cleanup", ADR-010): GET and the cache lookup treat an expired row as absent
 * whether or not the cleanup has run; a repeated request replaces the expired row; the scheduled cleanup deletes expired checks
 * with their items and findings, leaves the others, and takes the shared advisory lock, so a run that cannot take it is skipped.
 */
@SpringBootTest(properties = {
        "listing-guard.image.enabled=true",
        "listing-guard.image.rulesets.AMAZON=classpath:image/amazon-image-test.yaml",
        "listing-guard.image.rulesets.ETSY=classpath:image/etsy-image-test.yaml"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ImageRetentionCleanupIntegrationTest {

    private static final AtomicInteger NEXT = new AtomicInteger(700);

    @Autowired
    ImageRetentionCleanup cleanup;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactions;

    private static List<Part> parts() {
        return List.of(main(product(NEXT.incrementAndGet(), 300, "PNG", 0)), additional(notAnImage()));
    }

    private JsonNode check(List<Part> parts) throws Exception {
        return MAPPER.readTree(mvc.perform(request("AMAZON", parts).header(HEADER, KEY_1)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void expire(String id) {
        jdbc.update("UPDATE image_check SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day' "
                + "WHERE id = ?::uuid", id);
    }

    private int count(String table, String where, Object... args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Integer.class, args);
    }

    @Test
    void anExpiredCheckIsNotReadableEvenBeforeTheCleanupHasRun() throws Exception {
        String id = check(parts()).get("checkId").asText();
        mvc.perform(get(CHECKS + "/" + id).header(HEADER, KEY_1)).andExpect(status().isOk());

        expire(id);

        mvc.perform(get(CHECKS + "/" + id).header(HEADER, KEY_1)).andExpect(status().isNotFound());
        assertThat(count("image_check", "id = ?::uuid", id)).as("the row is still there").isEqualTo(1);
    }

    @Test
    void aRepeatedRequestReplacesAnExpiredCompleteResultInsteadOfReusingIt() throws Exception {
        List<Part> complete = List.of(main(product(NEXT.incrementAndGet(), 300, "PNG", 0)));
        JsonNode first = check(complete);
        assertThat(first.get("completeness").asText()).isEqualTo("COMPLETE");
        String oldId = first.get("checkId").asText();
        expire(oldId);

        JsonNode second = check(complete);

        assertThat(second.get("cached").asBoolean()).isFalse();
        assertThat(second.get("checkId").asText()).isNotEqualTo(oldId);
        assertThat(count("image_check", "id = ?::uuid", oldId)).as("the expired row was deleted").isZero();
        assertThat(count("image_check_item", "check_id = ?::uuid", oldId)).isZero();
        assertThat(check(complete).get("cached").asBoolean()).as("the new result is reusable").isTrue();
    }

    @Test
    void theCleanupDeletesExpiredChecksWithTheirItemsAndFindingsAndLeavesTheOthers() throws Exception {
        String expired = check(parts()).get("checkId").asText();
        String kept = check(parts()).get("checkId").asText();
        assertThat(count("image_check_item", "check_id = ?::uuid", expired)).isEqualTo(2);
        expire(expired);

        ImageRetentionCleanup.Outcome outcome = cleanup.runOnce();

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.deletedChecks()).isGreaterThanOrEqualTo(1);
        assertThat(count("image_check", "id = ?::uuid", expired)).isZero();
        assertThat(count("image_check_item", "check_id = ?::uuid", expired)).isZero();
        assertThat(count("image_check_finding", "check_id = ?::uuid", expired)).isZero();
        assertThat(count("image_check", "id = ?::uuid", kept)).isEqualTo(1);
        assertThat(count("image_check_item", "check_id = ?::uuid", kept)).isEqualTo(2);
    }

    @Test
    void aCleanupThatCannotTakeTheSharedAdvisoryLockIsSkippedNotWaitedFor() throws Exception {
        String expired = check(parts()).get("checkId").asText();
        expire(expired);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            ImageRetentionCleanup.Outcome outcome = transactions.execute(status -> {
                // this transaction holds the pair that ADR-010 reserves for retention cleanup
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", Object.class, TrademarkRetentionCleanup.LOCK_KEY_1,
                        TrademarkRetentionCleanup.LOCK_KEY_2);
                try {
                    return other.submit(cleanup::runOnce).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(outcome.skipped()).isTrue();
            assertThat(outcome.deletedChecks()).isZero();
            assertThat(count("image_check", "id = ?::uuid", expired)).as("nothing was deleted while the lock was held").isEqualTo(1);
        } finally {
            other.shutdownNow();
        }
        assertThat(cleanup.runOnce().skipped()).isFalse();
        assertThat(count("image_check", "id = ?::uuid", expired)).isZero();
    }
}

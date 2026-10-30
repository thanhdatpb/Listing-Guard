package com.thanhdatpb.listingguard.api.trademark;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.ApiTestKeys;
import com.thanhdatpb.listingguard.api.TestcontainersConfig;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** The scheduled cleanup, switched on: it deletes an expired result by itself, without any request. */
@SpringBootTest(properties = {"test.scheduled-cleanup=true", "listing-guard.trademark.retention.cleanup-enabled=true",
        "listing-guard.trademark.retention.cleanup-initial-delay=100ms", "listing-guard.trademark.retention.cleanup-interval=200ms",
        "listing-guard.trademark.retention.cleanup-batch-size=10"})
@Import({TestcontainersConfig.class, TrademarkReadyTestConfig.class})
class TrademarkCleanupScheduleIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    TrademarkCheckService service;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void theScheduleDeletesAnExpiredResultAndLeavesARetainedOne() throws Exception {
        assertThat(context.getBeansOfType(TrademarkCleanupSchedule.class)).hasSize(1);
        UUID client = jdbc.queryForObject("SELECT id FROM client WHERE name = ?", UUID.class, ApiTestKeys.CLIENT_1);
        UUID expired = service.check(client, request("Zorvex scheduled expired")).checkId();
        UUID retained = service.check(client, request("Zorvex scheduled retained")).checkId();
        jdbc.update("UPDATE trademark_check SET created_at = now() - interval '31 days', "
                + "expires_at = now() - interval '1 day' WHERE id = ?", expired);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (rows(expired) > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }

        assertThat(rows(expired)).as("the schedule removed the expired check").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trademark_check_finding WHERE check_id = ?", Integer.class, expired))
                .isZero();
        assertThat(rows(retained)).isEqualTo(1);
    }

    private int rows(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM trademark_check WHERE id = ?", Integer.class, id);
    }

    private CheckRequest request(String title) {
        ObjectNode listing = mapper.createObjectNode();
        listing.put("title", title + " " + TrademarkCheckApiIntegrationTest.unique());
        return new CheckRequest("ETSY", listing);
    }
}

package com.thanhdatpb.listingguard.api.plan;

import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import com.thanhdatpb.listingguard.api.client.ClientRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** What the tests of plans and quotas share: clients with a plan, usage rows, a clock they can set, listing bodies. */
public final class PlanTestSupport {

    /** The instant every plan test starts at: the middle of a month, so no test depends on the real date. */
    public static final Instant NOVEMBER_15 = Instant.parse("2026-11-15T10:00:00Z");
    public static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1);

    /** A client created for one test, with the key it calls the API with. */
    public record TestClient(String name, UUID id, String key) {
    }

    /** A UTC clock that a test moves. */
    public static final class MutableClock extends Clock {

        private volatile Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private PlanTestSupport() {
    }

    /** A new active client on the plan, with a key. Its name and key are random, so tests never share usage. */
    public static TestClient newClient(ClientProvisioning provisioning, ClientRepository clients, PlanCode plan) {
        String name = "plan" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String key = "lgtest" + UUID.randomUUID().toString().replace("-", "") + "x";
        provisioning.ensureKnownKey(name, key, "plan test");
        provisioning.setPlan(name, plan);
        return new TestClient(name, clients.findByName(name).orElseThrow().getId(), key);
    }

    /** The monthly quota the database holds for the plan: tests read the number, they do not repeat it. */
    public static int quota(JdbcTemplate jdbc, PlanCode plan) {
        return jdbc.queryForObject("SELECT monthly_quota FROM plan WHERE code = ?", Integer.class, plan.name());
    }

    public static void setUsed(JdbcTemplate jdbc, UUID clientId, LocalDate month, int used) {
        jdbc.update("INSERT INTO client_usage (client_id, period_start, used) VALUES (?, ?, ?) "
                + "ON CONFLICT (client_id, period_start) DO UPDATE SET used = EXCLUDED.used", clientId, month, used);
    }

    public static int used(JdbcTemplate jdbc, UUID clientId, LocalDate month) {
        return jdbc.query("SELECT used FROM client_usage WHERE client_id = ? AND period_start = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, clientId, month);
    }

    public static int countOf(JdbcTemplate jdbc, String table, UUID clientId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE client_id = ?", Integer.class, clientId);
    }

    /** Letters only, so the text can never look like a mark of the synthetic trademark catalog, and never repeats. */
    public static String unique() {
        String letters = "ghijklmnopqrstuv";
        StringBuilder out = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            out.append(letters.charAt(Character.digit(c, 16)));
        }
        return out.toString();
    }

    /** The request both endpoints take; a title is enough for a result to be stored. */
    public static String body(String title) {
        return "{\"marketplace\":\"ETSY\",\"listing\":{\"title\":\"" + title + "\"}}";
    }
}

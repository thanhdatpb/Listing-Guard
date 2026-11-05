package com.thanhdatpb.listingguard.api.plan;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The plan of a client: which packs it may use and how many results it may store in a UTC month (ADR-013).
 *
 * <p>A service that stores a result uses it in this order: {@link #requirePack} first (the pack; this is the only read of the
 * plan), then, if the answer is not in the cache, {@link #requireQuota} before the analysis runs, and {@link #charge} in
 * the same transaction that stores the result. The charge is a single conditional upsert, so concurrent requests cannot
 * take a client past its quota, and a transaction that rolls back (an identical request stored first, a failure) takes its
 * charge with it.
 *
 * <p>While {@code listing-guard.plans.enabled} is false every method returns at once without touching the database,
 * except {@link #usage}, which only reads. A deployment that hosts the code for itself is therefore unchanged.
 */
@Service
public class PlanService {

    /** The plan and its packs, as one snapshot, taken once per request. */
    private static final String ENTITLEMENT = """
            SELECT c.plan_code, p.monthly_quota, pp.pack_id
            FROM client c
            JOIN plan p ON p.code = c.plan_code
            LEFT JOIN plan_pack pp ON pp.plan_code = p.code
            WHERE c.id = ?""";

    /** Adds one unit unless the month is already at the quota; no row is touched when it is. */
    private static final String CHARGE_LIMITED = """
            INSERT INTO client_usage (client_id, period_start, used) VALUES (?, ?, 1)
            ON CONFLICT (client_id, period_start) DO UPDATE SET used = client_usage.used + 1
            WHERE client_usage.used < ?""";

    private static final String CHARGE_UNLIMITED = """
            INSERT INTO client_usage (client_id, period_start, used) VALUES (?, ?, 1)
            ON CONFLICT (client_id, period_start) DO UPDATE SET used = client_usage.used + 1""";

    /**
     * What one request may do: the client, its plan and its quota ({@code monthlyQuota} null is unlimited). When plans are
     * off {@code enforced} is false and {@link #requireQuota} and {@link #charge} do nothing with it.
     */
    public record Admission(UUID clientId, boolean enforced, String plan, Integer monthlyQuota) {
    }

    /** {@code packs} is sorted, so the packs of a response are in a fixed order. */
    private record Entitlement(String plan, Integer monthlyQuota, Set<String> packs) {
    }

    private final PlanProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PlanService(PlanProperties properties, JdbcTemplate jdbc, Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * Step 1: the client's plan must include the pack.
     *
     * @throws PlanNotAllowedException if it does not
     */
    public Admission requirePack(UUID clientId, Pack pack) {
        if (!enabled()) {
            return open(clientId);
        }
        Entitlement entitlement = entitlement(clientId);
        if (!entitlement.packs().contains(pack.id())) {
            throw new PlanNotAllowedException(entitlement.plan(), pack);
        }
        return new Admission(clientId, true, entitlement.plan(), entitlement.monthlyQuota());
    }

    /**
     * Step 3: refuses before the analysis when the month is already at the quota. Only an optimization to skip work that
     * would be refused; {@link #charge} is the guarantee.
     *
     * @throws QuotaExceededException if no unit is left this month
     */
    public void requireQuota(Admission admission) {
        if (!admission.enforced() || admission.monthlyQuota() == null) {
            return;
        }
        LocalDate period = currentPeriod();
        long used = used(admission.clientId(), period);
        if (used >= admission.monthlyQuota()) {
            throw exceeded(admission, used, period);
        }
    }

    /**
     * Step 5: counts one stored result. Must run inside the transaction that stores it, so that a rollback undoes the
     * charge.
     *
     * @throws QuotaExceededException if a concurrent request took the last unit after {@link #requireQuota}
     */
    public void charge(Admission admission) {
        if (!admission.enforced()) {
            return;
        }
        LocalDate period = currentPeriod();
        int stored = admission.monthlyQuota() == null
                ? jdbc.update(CHARGE_UNLIMITED, admission.clientId(), period)
                : jdbc.update(CHARGE_LIMITED, admission.clientId(), period, admission.monthlyQuota());
        if (stored == 0) {
            throw exceeded(admission, used(admission.clientId(), period), period);
        }
    }

    /** The client's plan, quota and use for the current UTC month. Read-only, whether plans are on or not. */
    public Usage usage(UUID clientId) {
        Entitlement entitlement = entitlement(clientId);
        LocalDate period = currentPeriod();
        long used = used(clientId, period);
        if (!enabled()) {
            return new Usage(false, entitlement.plan(), null, used, null, period, resetAt(period), Pack.allIds());
        }
        Integer quota = entitlement.monthlyQuota();
        Long remaining = quota == null ? null : Math.max(0L, quota - used);
        return new Usage(true, entitlement.plan(), quota, used, remaining, period, resetAt(period),
                List.copyOf(entitlement.packs()));
    }

    private static Admission open(UUID clientId) {
        return new Admission(clientId, false, null, null);
    }

    private QuotaExceededException exceeded(Admission admission, long used, LocalDate period) {
        return new QuotaExceededException(admission.plan(), admission.monthlyQuota(), used, resetAt(period));
    }

    private Entitlement entitlement(UUID clientId) {
        Entitlement found = jdbc.query(ENTITLEMENT, rs -> {
            String plan = null;
            Integer quota = null;
            Set<String> packs = new TreeSet<>();
            while (rs.next()) {
                plan = rs.getString("plan_code");
                int value = rs.getInt("monthly_quota");
                quota = rs.wasNull() ? null : value;
                String pack = rs.getString("pack_id");
                if (pack != null) {
                    packs.add(pack);
                }
            }
            return plan == null ? null : new Entitlement(plan, quota, packs);
        }, clientId);
        if (found == null) {
            throw new IllegalStateException("No client with id " + clientId);
        }
        return found;
    }

    private long used(UUID clientId, LocalDate period) {
        Long used = jdbc.query("SELECT used FROM client_usage WHERE client_id = ? AND period_start = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, clientId, period);
        return used == null ? 0L : used;
    }

    /** The first day of the current month in UTC. */
    LocalDate currentPeriod() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
    }

    private static Instant resetAt(LocalDate period) {
        return period.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}

package com.thanhdatpb.listingguard.api.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.check.ContentHasher;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.llm.JdbcLlmUsageStore;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.rag.analysis.PolicyAnalyzer;
import com.thanhdatpb.listingguard.rag.analysis.PolicyFinding;
import com.thanhdatpb.listingguard.rag.analysis.VerificationStats;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared tenant-scoped implementation for the API key and Console session entry points. */
@Service
public class PolicyChecks {
    private static final String ETSY = "ETSY";
    private static final String SELECT = "SELECT id, created_at, expires_at, result FROM policy_check WHERE client_id = ?";
    private static final List<String> FIELDS = List.of("title", "description", "tags", "materials", "who_made", "when_made", "category", "taxonomy_path");

    @Schema(name = "PolicyCheckResult")
    public record Result(UUID checkId, String marketplace, OffsetDateTime analyzedAt, OffsetDateTime expiresAt,
                         boolean cached, Analysis analysis) { }

    @Schema(name = "PolicyCheckAnalysis")
    public record Analysis(PolicyAnalyzer.Status status, PolicyAnalyzer.Reason reason,
                           @Schema(nullable = true) String analysisVersion,
                           @Schema(nullable = true) PolicyAnalyzer.Descriptor descriptor,
                           List<PolicyFinding> findings, VerificationStats verification) {
        private static Analysis of(PolicyAnalyzer.Result result) {
            return new Analysis(result.status(), result.reason(), result.analysisVersion(), result.descriptor(),
                    result.findings(), result.verification());
        }
    }

    public static final class Failure extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        public Failure(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() { return status; }
        public String code() { return code; }
    }

    private final ObjectProvider<PolicyAnalysisService> analyzer;
    private final NormalizerRegistry normalizers;
    private final ContentHasher hasher;
    private final PlanService plans;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final int retentionDays;
    private final int dailyLimit;
    private final JdbcLlmUsageStore usage;

    public PolicyChecks(ObjectProvider<PolicyAnalysisService> analyzer, NormalizerRegistry normalizers, ContentHasher hasher,
                        PlanService plans, JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper, Clock clock,
                        @Value("${listing-guard.analyzer.retention-days:0}") int retentionDays,
                        @Value("${listing-guard.analyzer.daily-attempt-limit:100}") int dailyLimit) {
        this.analyzer = analyzer;
        this.normalizers = normalizers;
        this.hasher = hasher;
        this.plans = plans;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
        this.clock = clock;
        this.retentionDays = retentionDays;
        this.dailyLimit = dailyLimit;
        this.usage = new JdbcLlmUsageStore(jdbc);
    }

    public boolean enabled() {
        return retentionDays > 0 && retentionDays <= 365 && dailyLimit > 0 && analyzer.getIfAvailable() != null;
    }

    public Result check(UUID client, PolicyCheckRequest request) {
        requireEnabled();
        if (!ETSY.equalsIgnoreCase(request.marketplace())) {
            throw new Failure(HttpStatus.UNPROCESSABLE_CONTENT, "policy-marketplace-unsupported", "Policy checks support Etsy only.");
        }
        String language = request.language() == null ? "en" : request.language();
        if (!("en".equals(language) || "vi".equals(language)) || request.listing() == null || !request.listing().isObject()) {
            throw new Failure(HttpStatus.BAD_REQUEST, "invalid-request", "Provide a listing object and a supported language (en or vi).");
        }
        var admission = plans.requirePack(client, Pack.POLICY_ANALYZER);
        var input = ((com.fasterxml.jackson.databind.node.ObjectNode) request.listing().deepCopy()).retain(FIELDS);
        var listing = normalizers.get(ETSY).normalize(input);
        String hash = hasher.hash(listing);
        PolicyAnalysisService service = analyzer.getObject();
        var identity = service.cacheVersion(language);
        if (identity.isPresent()) {
            var cached = cached(client, hash, identity.get());
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        plans.requireQuota(admission);
        if (!usage.reserve("policy-analyzer", LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), dailyLimit)) {
            throw new Failure(HttpStatus.TOO_MANY_REQUESTS, "policy-budget-exhausted", "The daily policy analysis budget is exhausted.");
        }
        var response = service.analyze(ETSY, language, listing);
        if (response.reason() == PolicyAnalyzer.Reason.ANALYZER_BUSY) {
            throw new Failure(HttpStatus.SERVICE_UNAVAILABLE, "analyzer-busy", "Policy analysis is busy. Try again later.");
        }
        String cacheVersion = response.status() == PolicyAnalyzer.Status.REVIEW_REQUIRED ? response.analysisVersion() : null;
        return transactions.execute(status -> save(admission, hash, cacheVersion, response));
    }

    public Result get(UUID client, UUID id) {
        requireEnabled();
        return find(SELECT + " AND id = ? AND expires_at > ?", false, client, id, now()).orElseThrow(() ->
                new Failure(HttpStatus.NOT_FOUND, "policy-check-not-found", "No retained policy check with this id."));
    }

    private Result save(PlanService.Admission admission, String hash, String cacheVersion, PolicyAnalyzer.Result response) {
        UUID client = admission.clientId();
        UUID id = UUID.randomUUID();
        OffsetDateTime created = now();
        OffsetDateTime expiry = created.plusDays(retentionDays);
        if (cacheVersion != null) {
            jdbc.update("""
                    DELETE FROM policy_check WHERE client_id = ? AND marketplace_code = 'ETSY' AND input_hash = ?
                         AND cache_version = ? AND expires_at <= ?
                    """, client, hash, cacheVersion, created);
        }
        int inserted = jdbc.update("""
                INSERT INTO policy_check (client_id, id, marketplace_code, input_hash, cache_version, result, created_at, expires_at)
                VALUES (?, ?, 'ETSY', ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (client_id, marketplace_code, input_hash, cache_version)
                WHERE cache_version IS NOT NULL DO NOTHING
                """, client, id, hash, cacheVersion, json(response), created, expiry);
        if (inserted == 0) {
            return cached(client, hash, cacheVersion).orElseThrow(() ->
                    new Failure(HttpStatus.SERVICE_UNAVAILABLE, "policy-cache-race", "Retry the policy check."));
        }
        if (cacheVersion != null) {
            plans.charge(admission);
        }
        return new Result(id, ETSY, created, expiry, false, Analysis.of(response));
    }

    private Optional<Result> cached(UUID client, String hash, String version) {
        return find(SELECT + " AND marketplace_code = 'ETSY' AND input_hash = ? AND cache_version = ? AND expires_at > ?",
                true, client, hash, version, now());
    }

    private Optional<Result> find(String sql, boolean cached, Object... args) {
        return jdbc.query(sql, (rs, row) -> {
            try {
                return new Result(rs.getObject("id", UUID.class), ETSY, rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class), cached,
                        Analysis.of(mapper.readValue(rs.getString("result"), PolicyAnalyzer.Result.class)));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Cannot read retained policy check");
            }
        }, args).stream().findFirst();
    }

    private String json(PolicyAnalyzer.Result response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize policy check");
        }
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new Failure(HttpStatus.NOT_FOUND, "policy-checks-disabled", "Policy checks are unavailable.");
        }
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    void purgeExpired() {
        jdbc.update("""
                DELETE FROM policy_check WHERE (client_id, id) IN
                (SELECT client_id, id FROM policy_check WHERE expires_at <= ? ORDER BY expires_at LIMIT 500
                 FOR UPDATE SKIP LOCKED)
                """, now());
    }
}

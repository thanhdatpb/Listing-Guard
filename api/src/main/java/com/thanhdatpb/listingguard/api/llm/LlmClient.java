package com.thanhdatpb.listingguard.api.llm;

import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one way the application talks to a language model (ADR-036). It walks an ordered list of providers, skips the ones that may
 * not see the data of the call or have no quota left, moves on to the next on any failure, caches validated answers and writes one
 * audit line per call. It decides nothing about listings: the caller validates the answer and treats {@link LlmUnavailableException}
 * as "the step did not run".
 *
 * <p>Off ({@link #disabled}) it has no provider and every call throws {@code DISABLED} without a request.
 */
public final class LlmClient {

    /** Logger name of the audit lines: purpose, provider, model, data kinds, outcome, tokens. Never content. */
    public static final String AUDIT_LOGGER = "com.thanhdatpb.listingguard.audit.llm";

    static final Duration RATE_LIMIT_COOLDOWN = Duration.ofSeconds(60);

    private static final Logger audit = LoggerFactory.getLogger(AUDIT_LOGGER);

    /** A provider with its limits and the kinds of data it may receive. */
    public record Endpoint(String id, LlmProvider provider, int requestsPerMinute, int requestsPerDay,
                           boolean listingTextAllowed, boolean personalDataAllowed) {

        boolean allows(Set<LlmDataClass> data) {
            return (!data.contains(LlmDataClass.LISTING_TEXT) || listingTextAllowed)
                    && (!data.contains(LlmDataClass.PERSONALIZATION) || personalDataAllowed);
        }
    }

    /** One call: why, what is asked, and which kinds of data the prompt contains. */
    public record Call(String purpose, LlmRequest request, Set<LlmDataClass> data) {

        public Call {
            data = data.isEmpty() ? EnumSet.noneOf(LlmDataClass.class) : EnumSet.copyOf(data);
        }
    }

    /** An answer, and where it came from; {@code cached} answers cost no request. */
    public record Result(String text, String providerId, String model, int inputTokens, int outputTokens, boolean cached) {
    }

    public record ProviderUsage(String id, String model, long used, int limit) {
    }

    /** Requests sent today (UTC): what the home page shows. */
    public record Usage(boolean enabled, long used, long limit, List<ProviderUsage> providers) {
    }

    private final boolean enabled;
    private final List<Endpoint> endpoints;
    private final LlmUsageStore usage;
    private final LlmCache cache;
    private final Clock clock;
    private final Map<String, ArrayDeque<Instant>> windows = new ConcurrentHashMap<>();
    private final Map<String, Instant> coolDowns = new ConcurrentHashMap<>();

    public LlmClient(List<Endpoint> endpoints, LlmUsageStore usage, LlmCache cache, Clock clock) {
        this(true, endpoints, usage, cache, clock);
    }

    private LlmClient(boolean enabled, List<Endpoint> endpoints, LlmUsageStore usage, LlmCache cache, Clock clock) {
        this.enabled = enabled;
        this.endpoints = List.copyOf(endpoints);
        this.usage = usage;
        this.cache = cache;
        this.clock = clock;
    }

    /** A client with no provider: every call is unavailable and nothing is sent. */
    public static LlmClient disabled(Clock clock) {
        return new LlmClient(false, List.of(), null, null, clock);
    }

    public boolean enabled() {
        return enabled;
    }

    /** The model ids of the providers that may receive these kinds of data, in the order a call tries them. */
    public List<String> modelIds(Set<LlmDataClass> data) {
        return endpoints.stream().filter(endpoint -> endpoint.allows(data)).map(endpoint -> endpoint.provider().modelId()).toList();
    }

    /** True when at least one provider may receive these kinds of data. */
    public boolean canServe(Set<LlmDataClass> data) {
        return enabled && endpoints.stream().anyMatch(endpoint -> endpoint.allows(data));
    }

    public Result complete(Call call) {
        if (!enabled) {
            throw new LlmUnavailableException(LlmUnavailableException.Reason.DISABLED);
        }
        List<Endpoint> eligible = endpoints.stream().filter(endpoint -> endpoint.allows(call.data())).toList();
        if (eligible.isEmpty()) {
            audit(call, "-", "-", "SKIPPED:NO_ELIGIBLE_PROVIDER", 0, 0);
            throw new LlmUnavailableException(LlmUnavailableException.Reason.NO_ELIGIBLE_PROVIDER);
        }
        boolean failed = false;
        for (Endpoint endpoint : eligible) {
            String model = endpoint.provider().modelId();
            String key = LlmCache.key(model, call.request());
            Optional<String> hit = cache.get(key);
            if (hit.isPresent()) {
                audit(call, endpoint.id(), model, "CACHE_HIT", 0, 0);
                return new Result(hit.get(), endpoint.id(), model, 0, 0, true);
            }
            Instant now = clock.instant();
            if (coolingDown(endpoint, now) || !takeMinute(endpoint, now)) {
                continue;
            }
            LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
            if (!usage.reserve(endpoint.id(), day, endpoint.requestsPerDay())) {
                releaseMinute(endpoint, now);
                continue;
            }
            try {
                LlmResponse response = endpoint.provider().complete(call.request());
                usage.addTokens(endpoint.id(), day, response.inputTokens(), response.outputTokens());
                cache.put(key, response.text());
                audit(call, endpoint.id(), model, "OK", response.inputTokens(), response.outputTokens());
                return new Result(response.text(), endpoint.id(), model, response.inputTokens(), response.outputTokens(), false);
            } catch (LlmException e) {
                failed = true;
                if (e.kind() == LlmException.Kind.RATE_LIMIT) {
                    coolDowns.put(endpoint.id(), now.plus(RATE_LIMIT_COOLDOWN));
                }
                audit(call, endpoint.id(), model, "FAILED:" + e.kind(), 0, 0);
            } catch (RuntimeException e) {
                failed = true;
                audit(call, endpoint.id(), model, "FAILED:ERROR", 0, 0);
            }
        }
        LlmUnavailableException.Reason reason = failed ? LlmUnavailableException.Reason.PROVIDERS_FAILED
                : LlmUnavailableException.Reason.QUOTA_EXHAUSTED;
        audit(call, "-", "-", "SKIPPED:" + reason, 0, 0);
        throw new LlmUnavailableException(reason);
    }

    /** Requests sent today by each provider, and the limits. Zero everywhere when the client is off. */
    public Usage usage() {
        if (!enabled) {
            return new Usage(false, 0, 0, List.of());
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Map<String, Long> used = usage.requestsOn(today);
        List<ProviderUsage> providers = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            providers.add(new ProviderUsage(endpoint.id(), endpoint.provider().modelId(), used.getOrDefault(endpoint.id(), 0L),
                    endpoint.requestsPerDay()));
        }
        return new Usage(true, providers.stream().mapToLong(ProviderUsage::used).sum(),
                providers.stream().mapToLong(ProviderUsage::limit).sum(), providers);
    }

    private boolean coolingDown(Endpoint endpoint, Instant now) {
        Instant until = coolDowns.get(endpoint.id());
        return until != null && until.isAfter(now);
    }

    /** Takes a slot of the sliding minute; false when the provider already sent its limit in the last 60 seconds. */
    private boolean takeMinute(Endpoint endpoint, Instant now) {
        ArrayDeque<Instant> window = windows.computeIfAbsent(endpoint.id(), id -> new ArrayDeque<>());
        synchronized (window) {
            Instant from = now.minusSeconds(60);
            while (!window.isEmpty() && !window.peekFirst().isAfter(from)) {
                window.pollFirst();
            }
            if (window.size() >= endpoint.requestsPerMinute()) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    private void releaseMinute(Endpoint endpoint, Instant now) {
        ArrayDeque<Instant> window = windows.get(endpoint.id());
        if (window != null) {
            synchronized (window) {
                window.removeLastOccurrence(now);
            }
        }
    }

    private static void audit(Call call, String provider, String model, String outcome, int inputTokens, int outputTokens) {
        audit.info("llm purpose={} provider={} model={} data={} outcome={} inputTokens={} outputTokens={}", call.purpose(), provider,
                model, call.data().stream().map(Enum::name).collect(Collectors.joining(",")), outcome, inputTokens, outputTokens);
    }
}

package com.thanhdatpb.listingguard.rag.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import java.net.URI;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal analysis only. Findings are review signals based on verified excerpts, never policy decisions. */
public final class PolicyAnalyzer {
    private static final String ALGORITHM = "policy-citation-1";
    private static final int MAX_RESPONSE_CHARS = 65_536;
    private static final JsonMapper JSON = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();

    public enum Status { REVIEW_REQUIRED, INCOMPLETE, INSUFFICIENT_EVIDENCE }

    public enum Reason {
        NONE, NO_POLICY_CONTEXT, INVALID_PROVENANCE, STALE_POLICY_SOURCE, UNSUPPORTED_LOCALE, UNSUPPORTED_MARKETPLACE,
        POLICY_VERSION_MISMATCH, NO_VERIFIED_FINDINGS, PARTIAL_VERIFICATION, LLM_TIMEOUT, LLM_ERROR, LLM_INVALID_OUTPUT,
        LLM_TRUNCATED, LLM_REFUSED, ANALYZER_BUSY, ANALYZER_TIMEOUT, RETRIEVAL_FAILED
    }

    public record Descriptor(AnalyzerDescriptor retrieval, String promptVersion, String promptHash, String llmModel,
                             String algorithmVersion, String queryLanguage) { }

    public record Result(Status status, Reason reason, String analysisVersion, Descriptor descriptor,
                         List<PolicyFinding> findings, VerificationStats verification) {
        public Result {
            findings = List.copyOf(findings);
        }

        public static Result incomplete(Reason reason) {
            return new Result(Status.INCOMPLETE, reason, null, null, List.of(), emptyStats());
        }
    }

    private final LlmProvider provider;

    public PolicyAnalyzer(LlmProvider provider) {
        this.provider = provider;
    }

    public Result analyze(AnalyzerProfile profile, AnalyzerDescriptor retrieval, String marketplace, String language,
                          Listing listing, List<AnalysisEvidence> evidence, LocalDate today) {
        var settings = profile.settings();
        if (!settings.marketplace().equals(marketplace)) {
            return Result.incomplete(Reason.UNSUPPORTED_MARKETPLACE);
        }
        if (language == null || !settings.probes().containsKey(language)) {
            return Result.incomplete(Reason.UNSUPPORTED_LOCALE);
        }
        if (evidence.isEmpty()) {
            return Result.incomplete(Reason.NO_POLICY_CONTEXT);
        }
        if (retrieval == null || !settings.version().equals(retrieval.profileVersion()) || !profile.hash().equals(retrieval.profileHash())
                || retrieval.corpusFingerprint() == null || !retrieval.corpusFingerprint().matches("[0-9a-f]{64}")
                || evidence.size() > settings.maxChunks()) {
            return Result.incomplete(Reason.INVALID_PROVENANCE);
        }
        var seen = new HashSet<Long>();
        for (AnalysisEvidence item : evidence) {
            Reason reason = validateEvidence(item, settings, today, seen);
            if (reason != Reason.NONE) {
                return Result.incomplete(reason);
            }
        }

        var prepared = PolicyAnalyzerPrompt.prepare(settings, listing, language, evidence);
        if (prepared.evidence().isEmpty()) {
            return Result.incomplete(Reason.NO_POLICY_CONTEXT);
        }
        if (Thread.currentThread().isInterrupted()) {
            return Result.incomplete(Reason.ANALYZER_TIMEOUT);
        }
        var descriptor = describe(profile, retrieval, language);
        String version = analysisVersion(descriptor);
        ParseResult parsed;
        try {
            LlmResponse response = provider.complete(prepared.request());
            Reason failure = completionFailure(response);
            if (failure != Reason.NONE) {
                return failure(version, descriptor, failure);
            }
            var parser = new PolicyFindingParser(Set.copyOf(settings.topics()), PolicyAnalyzerPrompt.FIELDS,
                    PolicyAnalyzerPrompt.MAX_FINDINGS, PolicyAnalyzerPrompt.MAX_OUTPUT_TEXT, true);
            parsed = parser.parse(response.text());
        } catch (LlmException e) {
            return failure(version, descriptor, switch (e.kind()) {
                case TIMEOUT -> Reason.LLM_TIMEOUT;
                case TRUNCATED -> Reason.LLM_TRUNCATED;
                case REFUSED -> Reason.LLM_REFUSED;
                case INVALID_OUTPUT -> Reason.LLM_INVALID_OUTPUT;
                case RATE_LIMIT, BAD_STATUS -> Reason.LLM_ERROR;
            });
        } catch (InvalidLlmOutputException e) {
            return failure(version, descriptor, Reason.LLM_INVALID_OUTPUT);
        }
        var verified = new CitationVerifier(settings.minQuoteChars(), settings.maxQuoteChars()).verify(parsed,
                prepared.evidence().values().stream().map(AnalysisEvidence::chunk).toList(),
                prepared.listing(), prepared.evidence());
        List<PolicyFinding> review = verified.findings().stream().map(finding -> reviewOnly(finding, language)).toList();
        if (verified.stats().totalDropped() > 0) {
            Reason reason = parsed.valid().isEmpty() ? Reason.LLM_INVALID_OUTPUT : Reason.PARTIAL_VERIFICATION;
            return new Result(Status.INCOMPLETE, reason, version, descriptor, review, verified.stats());
        }
        return new Result(review.isEmpty() ? Status.INSUFFICIENT_EVIDENCE : Status.REVIEW_REQUIRED,
                review.isEmpty() ? Reason.NO_VERIFIED_FINDINGS : Reason.NONE, version, descriptor, review, verified.stats());
    }

    private static Reason validateEvidence(AnalysisEvidence item, AnalyzerProfile.Settings profile, LocalDate today, Set<Long> seen) {
        var chunk = item.chunk();
        if (chunk == null || !profile.marketplace().equals(chunk.marketplace()) || !profile.sourceLocale().equals(item.sourceLocale())
                || item.documentId() <= 0 || chunk.chunkId() <= 0 || !seen.add(chunk.chunkId()) || item.sourceHash() == null
                || !item.sourceHash().matches("[0-9a-f]{64}") || chunk.content() == null
                || chunk.content().length() > profile.contextChars() || chunk.content().isBlank()
                || chunk.document() == null || chunk.document().isBlank() || chunk.document().length() > 255
                || chunk.sectionPath() == null || chunk.sectionPath().length() > 8192
                || !(chunk.sectionPath().equals(chunk.document()) || chunk.sectionPath().startsWith(chunk.document() + " > "))
                || chunk.sourceFile() == null || chunk.url() == null || chunk.url().length() > 8192 || item.fetchedAt() == null
                || item.fetchedAt().isAfter(today)) {
            return Reason.INVALID_PROVENANCE;
        }
        var source = profile.sources().get(chunk.sourceFile());
        if (source == null || !source.url().equals(chunk.url().split("#", 2)[0])) {
            return Reason.INVALID_PROVENANCE;
        }
        try {
            URI.create(chunk.url());
        } catch (IllegalArgumentException e) {
            return Reason.INVALID_PROVENANCE;
        }
        if (item.fetchedAt().isBefore(today.minusDays(profile.maxSnapshotAgeDays()))) {
            return Reason.STALE_POLICY_SOURCE;
        }
        if (!source.version().equals(chunk.version())) {
            return Reason.POLICY_VERSION_MISMATCH;
        }
        if (LocalDate.parse(source.version()).isAfter(item.fetchedAt())) {
            return Reason.INVALID_PROVENANCE;
        }
        return Reason.NONE;
    }

    private static Reason completionFailure(LlmResponse response) {
        if (response == null) {
            return Reason.LLM_INVALID_OUTPUT;
        }
        if ("length".equals(response.finishReason()) || response.text().length() > MAX_RESPONSE_CHARS || response.outputTokens() > 1500) {
            return Reason.LLM_TRUNCATED;
        }
        if ("content_filter".equals(response.finishReason()) || "refusal".equals(response.finishReason())) {
            return Reason.LLM_REFUSED;
        }
        return "stop".equals(response.finishReason()) ? Reason.NONE : Reason.LLM_INVALID_OUTPUT;
    }

    private static PolicyFinding reviewOnly(PolicyFinding finding, String language) {
        boolean vi = "vi".equals(language);
        return new PolicyFinding(finding.ruleId(), finding.severity(), finding.field(), null,
                vi ? "Cần đối chiếu trường này với đoạn chính sách được trích dẫn."
                        : "Review this field against the cited policy excerpt.",
                vi ? "Đọc nguồn chính sách và xác minh cách áp dụng cho listing."
                        : "Read the policy source and verify how it applies to the listing.",
                finding.citation());
    }

    public Descriptor describe(AnalyzerProfile profile, AnalyzerDescriptor retrieval, String language) {
        return new Descriptor(retrieval, PolicyAnalyzerPrompt.VERSION, PolicyAnalyzerPrompt.hash(profile.settings()),
                provider.modelId(), ALGORITHM, language);
    }

    public static String analysisVersion(Descriptor descriptor) {
        try {
            return "policy-analyzer:" + AnalyzerProfile.sha256(JSON.writeValueAsBytes(descriptor)).substring(0, 16);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the policy analyzer descriptor");
        }
    }

    private static Result failure(String version, Descriptor descriptor, Reason reason) {
        return new Result(Status.INCOMPLETE, reason, version, descriptor, List.of(), emptyStats());
    }

    private static VerificationStats emptyStats() {
        return new VerificationStats(0, 0, Map.of(), 0);
    }
}

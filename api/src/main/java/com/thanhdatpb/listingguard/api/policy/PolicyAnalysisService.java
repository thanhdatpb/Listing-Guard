package com.thanhdatpb.listingguard.api.policy;

import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.rag.analysis.AnalysisEvidence;
import com.thanhdatpb.listingguard.rag.analysis.PolicyAnalyzer;
import com.thanhdatpb.listingguard.rag.analysis.PolicyAnalyzer.Reason;
import com.thanhdatpb.listingguard.rag.analysis.PolicyAnalyzer.Result;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Optional internal orchestration. No controller or deterministic check depends on this service. */
@Service
@ConditionalOnProperty(prefix = "listing-guard.analyzer", name = "enabled", havingValue = "true")
public class PolicyAnalysisService {
    private final Semaphore permits = new Semaphore(4);
    private final AnalyzerPolicyRetrieval retrieval;
    private final PolicyAnalyzer analyzer;
    private final Clock clock;

    public PolicyAnalysisService(AnalyzerPolicyRetrieval retrieval, @Qualifier("policyLlmProvider") LlmProvider provider,
                                 Clock clock) {
        this.retrieval = retrieval;
        this.analyzer = new PolicyAnalyzer(provider);
        this.clock = clock;
    }

    public java.util.Optional<String> cacheVersion(String language) {
        return retrieval.currentDescriptor()
                .map(descriptor -> PolicyAnalyzer.analysisVersion(analyzer.describe(retrieval.profile(), descriptor, language)));
    }

    public Result analyze(String marketplace, String language, Listing listing) {
        if (!permits.tryAcquire()) {
            return Result.incomplete(Reason.ANALYZER_BUSY);
        }
        var task = new FutureTask<>(() -> analyzeOnce(marketplace, language, listing));
        Thread.startVirtualThread(() -> {
            try {
                task.run();
            } finally {
                permits.release();
            }
        });
        try {
            return task.get(40, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            return Result.incomplete(Reason.ANALYZER_TIMEOUT);
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return Result.incomplete(Reason.ANALYZER_TIMEOUT);
        } catch (ExecutionException e) {
            return Result.incomplete(Reason.RETRIEVAL_FAILED);
        }
    }

    private Result analyzeOnce(String marketplace, String language, Listing listing) {
        var snapshot = retrieval.retrieve(marketplace, language, listing);
        if (snapshot.status() != AnalyzerPolicyRetrieval.Status.READY) {
            return Result.incomplete(switch (snapshot.status()) {
                case READY -> throw new IllegalStateException("Unexpected ready snapshot");
                case UNSUPPORTED_MARKETPLACE -> Reason.UNSUPPORTED_MARKETPLACE;
                case UNSUPPORTED_LOCALE -> Reason.UNSUPPORTED_LOCALE;
                case INVALID_PROVENANCE -> Reason.INVALID_PROVENANCE;
                case STALE_CORPUS -> Reason.STALE_POLICY_SOURCE;
                case POLICY_VERSION_MISMATCH -> Reason.POLICY_VERSION_MISMATCH;
                case MISSING_CORPUS, EMPTY_CORPUS, NO_USABLE_EVIDENCE -> Reason.NO_POLICY_CONTEXT;
                case RETRIEVAL_TIMEOUT -> Reason.ANALYZER_TIMEOUT;
                case EMBEDDING_NOT_CONFIGURED, CORPUS_MODEL_MISMATCH, CORPUS_PIPELINE_MISMATCH, EMBEDDING_FAILED,
                     RETRIEVAL_ERROR -> Reason.RETRIEVAL_FAILED;
            });
        }
        var evidence = snapshot.evidence().stream().map(row ->
                new AnalysisEvidence(row.chunk(), row.documentId(), row.sourceHash(), row.fetchedAt(), row.sourceLocale())).toList();
        return analyzer.analyze(retrieval.profile(), snapshot.descriptor(), marketplace.toUpperCase(Locale.ROOT),
                language.toLowerCase(Locale.ROOT), listing, evidence, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}

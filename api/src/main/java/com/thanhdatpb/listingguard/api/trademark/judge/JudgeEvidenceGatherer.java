package com.thanhdatpb.listingguard.api.trademark.judge;

import com.thanhdatpb.listingguard.api.trademark.judge.JudgeEvidence.TsdrState;
import com.thanhdatpb.listingguard.api.trademark.judge.JudgeEvidence.VerifiedState;
import com.thanhdatpb.listingguard.trademark.Evidence;
import com.thanhdatpb.listingguard.trademark.RiskAdjustment;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.DistinctivenessSignals;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Collects the evidence pack of one finding in plain code (ADR-051): no model, no network call, no rule. The catalog signals come from
 * the mark the scan matched; the product-class overlap from the ruleset's own product type mapping; the TSDR status from the table the
 * {@code tsdr-status} job fills (never from the USPTO); the verified decision from this client's own reviews. A fact it cannot get is
 * left {@code null}, and the evidence says {@code unknown}.
 */
@Component
public class JudgeEvidenceGatherer {

    /** The last TSDR status of source records, from the table the {@code tsdr-status} job fills; empty when there is none or it is off. */
    public interface TsdrSource {
        Optional<TsdrState> latest(List<String> sourceIds);
    }

    /** This client's own verified decisions (ADR-044). */
    public interface ReviewSource {

        /** The most recent unexpired, unrevoked decision for a finding with this rule, kind of field and mark. */
        VerifiedState nearest(UUID clientId, String marketplace, String ruleId, String fieldKind, String mark);

        /**
         * A short digest of the client's active verified trademark cases, or the empty string when verified cases are off. It is part
         * of the analysis version of a check judged with evidence, so a result never outlives the decisions it was judged with.
         */
        String digest(UUID clientId);
    }

    private final TsdrSource tsdr;
    private final ReviewSource reviews;

    @Autowired
    public JudgeEvidenceGatherer(TsdrSource tsdr, ReviewSource reviews) {
        this.tsdr = tsdr;
        this.reviews = reviews;
    }

    /** A gatherer with no TSDR table and no verified cases: both are {@code unknown}. */
    public static JudgeEvidenceGatherer none() {
        return new JudgeEvidenceGatherer(sourceIds -> Optional.empty(), new ReviewSource() {
            @Override
            public VerifiedState nearest(UUID clientId, String marketplace, String ruleId, String fieldKind, String mark) {
                return VerifiedState.NOT_KNOWN;
            }

            @Override
            public String digest(UUID clientId) {
                return "";
            }
        });
    }

    /**
     * @param mark the catalog mark the scan matched, null when the catalog could not be asked
     */
    public JudgeEvidence gather(JudgeContext context, Evidence finding, CatalogMark mark) {
        DistinctivenessSignals signals = signals(finding, mark);
        Integer words = signals != null && signals.wordCount() != null ? signals.wordCount()
                : mark == null ? null : mark.tokenCount();
        List<String> sourceIds = finding.sourceIds().isEmpty() && mark != null ? mark.sourceIds() : finding.sourceIds();
        return new JudgeEvidence(signals == null ? null : signals.tier(), signals == null ? null : signals.sameMarkCount(),
                signals == null ? null : signals.sameMarkClassCount(), signals == null ? null : signals.markKind(),
                signals == null ? null : signals.dictionaryWord(), words, overlap(context, mark),
                tsdr.latest(sourceIds).orElse(null), verified(context, finding));
    }

    /** The digest of the client's verified decisions for the analysis version; empty when there is none to add. */
    public String digest(JudgeContext context) {
        return context == null || context.clientId() == null ? "" : reviews.digest(context.clientId());
    }

    private static DistinctivenessSignals signals(Evidence finding, CatalogMark mark) {
        if (mark != null && mark.distinctiveness() != null) {
            return mark.distinctiveness();
        }
        // a finding the calibration lowered carries the signals it used
        return finding.riskAdjustments().stream().map(RiskAdjustment::distinctiveness).filter(Objects::nonNull).findFirst()
                .orElse(null);
    }

    private static Boolean overlap(JudgeContext context, CatalogMark mark) {
        if (context.productClasses() == null || context.productClasses().isEmpty() || mark == null || mark.niceClasses().isEmpty()) {
            return null;
        }
        return mark.niceClasses().stream().anyMatch(context.productClasses()::contains);
    }

    private VerifiedState verified(JudgeContext context, Evidence finding) {
        String what = finding.matchedMark() != null ? finding.matchedMark() : finding.normalizedToken();
        if (context.clientId() == null || context.marketplace() == null || finding.field() == null || what == null) {
            return VerifiedState.NOT_KNOWN;
        }
        String fieldKind = finding.field().replaceAll("\\[\\d+]", "");
        return reviews.nearest(context.clientId(), context.marketplace(), finding.ruleId(), fieldKind, what.toLowerCase(Locale.ROOT));
    }
}

package com.thanhdatpb.listingguard.api.rewrite;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.ruleengine.RuleRun;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Judges a listing, or a candidate copy of one, with every check the editor shows: the listing rules of the marketplace and the
 * trademark check against the served catalog and the client's watchlist (ADR-037). It goes through
 * {@link CheckService#evaluate} and {@link TrademarkCheckService#evaluate}, which store nothing, cache nothing and use no quota.
 */
@Component
@ConditionalOnProperty(prefix = "listing-guard.llm.rewrite", name = "enabled", havingValue = "true")
public class ListingEvaluator {

    /** The two outcomes. {@code complete} is false when a rule failed to run or the trademark check is incomplete. */
    public record Evaluation(RuleRun rules, TrademarkResult trademark) {

        public boolean complete() {
            return rules.complete() && trademark.completeness() == Completeness.COMPLETE;
        }

        public List<Finding> errors() {
            return rules.findings().stream().filter(finding -> finding.severity() == Severity.ERROR).toList();
        }

        /** The ERROR findings of the rules plus every trademark finding: what a rewrite is meant to reduce. */
        public int problems() {
            return errors().size() + trademark.findings().size();
        }

        /**
         * One entry per trademark finding: the rule, the kind of field (a tag's position is dropped, because positions move) and
         * the matched catalog mark, or the normalized token for a finding without a mark. A candidate may keep a signature the
         * original already had and may not add one.
         */
        public Set<String> trademarkSignatures() {
            Set<String> signatures = new HashSet<>();
            for (TrademarkFinding finding : trademark.findings()) {
                signatures.add(signature(finding));
            }
            return signatures;
        }

        static String signature(TrademarkFinding finding) {
            var evidence = finding.evidence();
            String field = evidence.field() == null ? "" : evidence.field().replaceAll("\\[\\d+]", "");
            String what = evidence.matchedMark() != null ? evidence.matchedMark() : evidence.normalizedToken();
            return finding.ruleId() + "|" + field + "|" + (what == null ? "" : what.toLowerCase(Locale.ROOT));
        }
    }

    private final CheckService checks;
    private final TrademarkCheckService trademarks;

    public ListingEvaluator(CheckService checks, TrademarkCheckService trademarks) {
        this.checks = checks;
        this.trademarks = trademarks;
    }

    /**
     * @param listing the standard listing JSON of {@code docs/input-schema.md}
     * @throws com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException    if the plan lacks the listing-rules or trademark-risk pack
     * @throws com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException if no catalog is served and the client has no watchlist
     */
    public Evaluation evaluate(UUID clientId, String marketplace, ObjectNode listing) {
        CheckRequest request = new CheckRequest(marketplace, listing);
        return new Evaluation(checks.evaluate(clientId, request), trademarks.evaluate(clientId, request));
    }
}

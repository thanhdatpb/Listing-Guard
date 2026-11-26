package com.thanhdatpb.listingguard.api.console.phrase;

import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningResponse;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningResponse.PhraseFindingView;
import com.thanhdatpb.listingguard.api.phrase.PhraseScreeningResponse.PhraseRowView;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.PhraseScreening.Verdict;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded, process-local, account- and session-scoped results. No servlet session serialization. */
@Component
@EnableScheduling
@ConditionalOnExpression("${listing-guard.console.enabled:false} && ${listing-guard.phrase-screening.enabled:false}")
public class PhraseConsoleResults {
    static final Duration TTL = Duration.ofMinutes(59);
    static final int MAX_RESULTS = 16;

    record Owner(UUID client, UUID account, String session) {}
    public record Finding(PhraseFindingView view, FeedbackTarget target) {
        public String riskMessage() {
            return "phrase.risk." + view.risk().name();
        }

        public String sourceMessage() {
            return "phrase.source." + view.source().name();
        }
    }
    public record Row(PhraseRowView view, List<Finding> findings) {
        public boolean exportable() {
            return view.verdict() == Verdict.CLEAR && view.completeness() == Completeness.COMPLETE;
        }
    }
    public record Result(UUID id, Owner owner, PhraseScreeningResponse response, List<Row> rows, Instant expires) {
        List<Row> forVerdict(Verdict verdict) {
            return rows.stream().filter(row -> row.view().verdict() == verdict).toList();
        }
    }

    private final Clock clock;
    private final LinkedHashMap<UUID, Result> entries = new LinkedHashMap<>();

    public PhraseConsoleResults(Clock clock) {
        this.clock = clock;
    }

    synchronized Result store(Owner owner, PhraseScreeningResponse response) {
        expire();
        entries.values().removeIf(result -> result.owner().equals(owner));
        var id = UUID.randomUUID();
        int position = 0;
        List<Row> rows = new ArrayList<>();
        for (var row : response.phrases()) {
            List<Finding> findings = new ArrayList<>();
            for (var finding : row.findings()) {
                findings.add(new Finding(finding, new FeedbackTarget(FeedbackTarget.PHRASE_PACK, id, position++)));
            }
            findings.sort(Comparator.comparing((Finding finding) -> finding.view().risk()).reversed());
            rows.add(new Row(row, List.copyOf(findings)));
        }
        rows.sort(Comparator.comparing(row -> row.view().searchVolume(), Comparator.nullsLast(Comparator.reverseOrder())));
        var result = new Result(id, owner, response, List.copyOf(rows), clock.instant().plus(TTL));
        if (entries.size() == MAX_RESULTS) {
            entries.remove(entries.keySet().iterator().next());
        }
        entries.put(id, result);
        return result;
    }

    synchronized Optional<Result> find(Owner owner, UUID id) {
        expire();
        return Optional.ofNullable(entries.get(id)).filter(result -> result.owner().equals(owner));
    }

    @Scheduled(fixedDelay = 30_000)
    public synchronized void expire() {
        Instant now = clock.instant();
        entries.values().removeIf(result -> !result.expires().isAfter(now));
    }
}

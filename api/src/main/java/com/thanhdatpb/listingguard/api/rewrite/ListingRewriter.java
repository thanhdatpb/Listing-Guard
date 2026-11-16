package com.thanhdatpb.listingguard.api.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.rewrite.ListingEvaluator.Evaluation;
import com.thanhdatpb.listingguard.api.rewrite.RewriteOutcome.Status;
import com.thanhdatpb.listingguard.api.rewrite.RewritePrompt.Problem;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.rag.llm.LlmException;
import com.thanhdatpb.listingguard.rag.llm.LlmProvider;
import com.thanhdatpb.listingguard.rag.llm.LlmResponse;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Asks the language model for up to three rewrites of the title (and replacements for faulty tags) of a listing that has an ERROR
 * finding or a trademark finding, and lets through only the ones the checks accept (ADR-037).
 *
 * <p>The model proposes; the checks decide. Each candidate is applied to a copy of the listing and judged by {@link ListingEvaluator}:
 * every listing rule must run and none may report an ERROR, the trademark check must be complete and must not report a finding the
 * original did not have, and the copy must have fewer problems than the original. A candidate that is empty, unchanged, longer in
 * words, stuffed with a repeated word, or built on a tag that no longer holds the old value is dropped before the checks run. Nothing
 * unverified is ever returned: when the model or a check cannot run the outcome is {@link Status#UNAVAILABLE} and carries no candidate.
 *
 * <p>The listing is sent to the provider only as its title and tags with the problems, and is never stored or logged here.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.llm.rewrite", name = "enabled", havingValue = "true")
public class ListingRewriter {

    private static final Logger log = LoggerFactory.getLogger(ListingRewriter.class);
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*");
    private static final int MAX_REPEAT = 2;
    private static final int MAX_VALUE_CHARS = 200;

    private final ObjectProvider<LlmProvider> provider;
    private final ListingEvaluator evaluator;
    private final RewriteRunStore runs;
    private final RewriteRateLimit limit;
    private final RewriteProperties properties;
    private final ObjectMapper mapper;

    public ListingRewriter(ObjectProvider<LlmProvider> provider, ListingEvaluator evaluator, RewriteRunStore runs,
                           RewriteRateLimit limit, RewriteProperties properties, ObjectMapper mapper) {
        this.provider = provider;
        this.evaluator = evaluator;
        this.runs = runs;
        this.limit = limit;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** True when a provider is configured (ADR-036); without one the console shows no button. */
    public boolean available() {
        return provider.getIfAvailable() != null;
    }

    /** Whether a listing of this size may be sent: a larger one gets no button. */
    public boolean fits(String listingJson) {
        return listingJson != null && listingJson.length() <= properties.maxListingChars();
    }

    /**
     * @param listing the standard listing JSON the seller's page holds
     * @param account the console account, or null
     * @param anchor  the finding the press came from (ADR-034), or null
     */
    public RewriteOutcome suggest(UUID clientId, UUID account, String marketplace, ObjectNode listing, FeedbackTarget anchor) {
        String title = listing.path("title").asText("").strip();
        LlmProvider model = provider.getIfAvailable();
        if (model == null || !fits(listing.toString())) {
            return RewriteOutcome.of(Status.UNAVAILABLE, title);
        }
        List<IndexedTag> tags = tags(listing);
        try {
            Evaluation base = evaluator.evaluate(clientId, marketplace, listing);
            if (base.problems() == 0) {
                return RewriteOutcome.of(Status.NOT_NEEDED, title);
            }
            if (!limit.tryAcquire(clientId)) {
                return RewriteOutcome.of(Status.RATE_LIMITED, title);
            }
            List<RewritePrompt.Raw> raw = ask(model, marketplace, title, tags, base);
            if (raw == null) {
                return RewriteOutcome.of(Status.UNAVAILABLE, title);
            }
            List<RewriteCandidate> safe = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (RewritePrompt.Raw candidate : raw) {
                Optional<RewriteCandidate> checked = verify(clientId, marketplace, listing, title, tags, base, candidate,
                        safe.size() + 1, seen);
                checked.ifPresent(safe::add);
            }
            UUID run = runs.record(clientId, account, marketplace, model.modelId(), safe.size(), anchor);
            return new RewriteOutcome(safe.isEmpty() ? Status.NONE_SAFE : Status.SUGGESTIONS, safe, run, title);
        } catch (PlanNotAllowedException | TrademarkUnavailableException e) {
            // a pack the plan lacks or a catalog that is not served: the candidates cannot be verified, so none is shown
            log.info("Rewrite suggestions not available: {}", e.getClass().getSimpleName());
            return RewriteOutcome.of(Status.UNAVAILABLE, title);
        }
    }

    private List<RewritePrompt.Raw> ask(LlmProvider model, String marketplace, String title, List<IndexedTag> tags, Evaluation base) {
        List<String> tagTexts = tags.stream().map(IndexedTag::text).toList();
        try {
            LlmResponse response = model.complete(RewritePrompt.request(mapper, properties, marketplace, title, tagTexts,
                    problems(base)));
            List<RewritePrompt.Raw> parsed = RewritePrompt.parse(mapper, response.text());
            if (parsed == null) {
                log.info("Rewrite suggestions: the model's answer was not the expected JSON");
            }
            return parsed;
        } catch (LlmException e) {
            // the message never carries listing text (LlmException contract), the kind is enough to diagnose
            log.info("Rewrite suggestions: the model call failed ({})", e.kind());
            return null;
        }
    }

    /** The findings the call is about: every ERROR of the rules and every trademark finding, with the seller's own text. */
    private static List<Problem> problems(Evaluation base) {
        List<Problem> out = new ArrayList<>();
        for (Finding finding : base.errors()) {
            out.add(new Problem(finding.ruleId(), finding.field() == null ? "" : finding.field(), cut(finding.value()),
                    finding.message()));
        }
        for (TrademarkFinding finding : base.trademark().findings()) {
            out.add(new Problem(finding.ruleId(), finding.evidence().field() == null ? "" : finding.evidence().field(),
                    cut(finding.evidence().snippet()), finding.message()));
        }
        return out;
    }

    private static String cut(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_VALUE_CHARS ? value : value.substring(0, MAX_VALUE_CHARS);
    }

    private Optional<RewriteCandidate> verify(UUID clientId, String marketplace, ObjectNode listing, String title,
                                              List<IndexedTag> tags, Evaluation base, RewritePrompt.Raw raw, int number,
                                              Set<String> seen) {
        String newTitle = null;
        if (raw.title() != null) {
            newTitle = raw.title().strip();
            if (!acceptableTitle(newTitle, title)) {
                return Optional.empty();
            }
        }
        ObjectNode copy = listing.deepCopy();
        if (newTitle != null) {
            copy.put("title", newTitle);
        }
        List<RewriteCandidate.TagChange> changes = new ArrayList<>();
        Set<Integer> changed = new HashSet<>();
        for (RewritePrompt.Raw.Tag tag : raw.tags()) {
            Optional<RewriteCandidate.TagChange> change = tagChange(copy, tags, tag, changed);
            if (change.isEmpty()) {
                return Optional.empty();
            }
            changes.add(change.get());
        }
        if (newTitle == null && changes.isEmpty()) {
            return Optional.empty();
        }
        if (!seen.add(String.valueOf(newTitle) + "\n" + changes)) {
            return Optional.empty();
        }
        Evaluation judged = evaluator.evaluate(clientId, marketplace, copy);
        if (!accepted(base, judged)) {
            return Optional.empty();
        }
        return Optional.of(new RewriteCandidate(number, newTitle, changes));
    }

    /** The rules and the trademark check agree: complete, no ERROR, no new trademark finding, and fewer problems than before. */
    static boolean accepted(Evaluation base, Evaluation judged) {
        return judged.complete() && judged.errors().isEmpty()
                && base.trademarkSignatures().containsAll(judged.trademarkSignatures())
                && judged.problems() < base.problems();
    }

    private static boolean acceptableTitle(String candidate, String original) {
        if (candidate.isEmpty() || !oneLine(candidate) || candidate.equals(original)) {
            return false;
        }
        return notLongerOrStuffed(candidate, original);
    }

    /** No more words than the original, and no word repeated more than twice unless the original already repeated it. */
    static boolean notLongerOrStuffed(String candidate, String original) {
        List<String> now = words(candidate);
        List<String> before = words(original);
        if (now.isEmpty() || now.size() > before.size()) {
            return false;
        }
        Map<String, Integer> was = counts(before);
        for (Map.Entry<String, Integer> entry : counts(now).entrySet()) {
            if (entry.getValue() > Math.max(MAX_REPEAT, was.getOrDefault(entry.getKey(), 0))) {
                return false;
            }
        }
        return true;
    }

    private Optional<RewriteCandidate.TagChange> tagChange(ObjectNode copy, List<IndexedTag> tags, RewritePrompt.Raw.Tag tag,
                                                           Set<Integer> changed) {
        if (tag.index() < 0 || tag.index() >= tags.size() || !changed.add(tag.index())) {
            return Optional.empty();
        }
        IndexedTag current = tags.get(tag.index());
        String to = tag.to().strip();
        if (!current.text().equals(tag.from().strip()) || to.isEmpty() || !oneLine(to) || to.equals(current.text())
                || words(to).size() > words(current.text()).size()) {
            return Optional.empty();
        }
        for (IndexedTag other : tags) {
            if (other != current && other.text().equalsIgnoreCase(to)) {
                return Optional.empty();
            }
        }
        if (!(copy.get("tags") instanceof ArrayNode array)) {
            return Optional.empty();
        }
        array.set(current.rawIndex(), to);
        return Optional.of(new RewriteCandidate.TagChange(tag.index(), current.text(), to));
    }

    private static boolean oneLine(String text) {
        return text.indexOf('\n') < 0 && text.indexOf('\r') < 0;
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private static Map<String, Integer> counts(List<String> words) {
        Map<String, Integer> counts = new HashMap<>();
        words.forEach(word -> counts.merge(word, 1, Integer::sum));
        return counts;
    }

    /** A tag as the findings count it: {@code index} is its place in the cleaned list, {@code rawIndex} its place in the JSON array. */
    record IndexedTag(int rawIndex, String text) {
    }

    /** The tags of the listing with blank ones left out, as the rules count them (ADR-014: list indexes count the cleaned list). */
    static List<IndexedTag> tags(ObjectNode listing) {
        List<IndexedTag> tags = new ArrayList<>();
        JsonNode array = listing.get("tags");
        if (array != null && array.isArray()) {
            for (int i = 0; i < array.size(); i++) {
                String text = array.get(i).asText("").strip();
                if (!text.isEmpty()) {
                    tags.add(new IndexedTag(i, text));
                }
            }
        }
        return tags;
    }
}

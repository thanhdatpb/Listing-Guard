package com.thanhdatpb.listingguard.api.rewrite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import com.thanhdatpb.listingguard.api.llm.LlmClient;
import com.thanhdatpb.listingguard.api.llm.LlmDataClass;
import com.thanhdatpb.listingguard.api.llm.LlmUnavailableException;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.rewrite.ListingEvaluator.Evaluation;
import com.thanhdatpb.listingguard.api.rewrite.RewriteOutcome.Status;
import com.thanhdatpb.listingguard.api.rewrite.RewriteOutcome.Stop;
import com.thanhdatpb.listingguard.api.rewrite.RewritePrompt.Problem;
import com.thanhdatpb.listingguard.api.rewrite.RewritePrompt.Reason;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.core.finding.Finding;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>With {@code max-rounds} above 1 (ADR-050) a round that was read but had no candidate that passed is followed by another call,
 * which tells the model why each candidate was rejected: the step, the rule, the field and the limit, and for a new trademark finding
 * only that a registered mark was added, never its name. The checks stay the only gate: every candidate of every round is judged the
 * same way, and a round after the first runs only when the one before was readable and had no candidate that passed. With
 * {@code max-rounds} 1 the prompt, the number of calls and the result are those of ADR-037.
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
    private static final int MAX_FEEDBACK_CHARS = 300;

    /** What the prompt contains (ADR-036 item 6): only a provider whose listing-text flag is on may receive it. */
    static final Set<LlmDataClass> DATA = Set.of(LlmDataClass.LISTING_TEXT);

    /** The audit purpose of the first round (ADR-036 item 7) and of every round after it (ADR-050). */
    static final String PURPOSE = "rewrite_suggestion";
    static final String REPAIR_PURPOSE = "rewrite_repair";

    private final LlmClient client;
    private final ListingEvaluator evaluator;
    private final RewriteRunStore runs;
    private final RewriteRateLimit limit;
    private final RewriteProperties properties;
    private final ObjectMapper mapper;

    public ListingRewriter(LlmClient client, ListingEvaluator evaluator, RewriteRunStore runs,
                           RewriteRateLimit limit, RewriteProperties properties, ObjectMapper mapper) {
        this.client = client;
        this.evaluator = evaluator;
        this.runs = runs;
        this.limit = limit;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * True when the shared LLM client of ADR-036 is on and at least one provider may receive listing text; otherwise the console
     * shows no button and nothing is sent.
     */
    public boolean available() {
        return client.canServe(DATA);
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
        if (!available() || !fits(listing.toString())) {
            return RewriteOutcome.of(Status.UNAVAILABLE, title);
        }
        List<IndexedTag> tags = tags(listing);
        int max = properties.maxRounds();
        int round = 0;
        try {
            Evaluation base = evaluator.evaluate(clientId, marketplace, listing);
            if (base.problems() == 0) {
                return RewriteOutcome.of(Status.NOT_NEEDED, title);
            }
            if (!limit.tryAcquire(clientId)) {
                return RewriteOutcome.of(Status.RATE_LIMITED, title);
            }
            List<Problem> problems = problems(base);
            List<RewritePrompt.Round> previous = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            while (round < max) {
                round++;
                Answer answer = ask(marketplace, title, tags, problems, previous, round);
                if (answer == null) {
                    return RewriteOutcome.ended(Status.UNAVAILABLE, List.of(), null, title, round, max, Stop.UNAVAILABLE);
                }
                Judged judged = judge(clientId, marketplace, listing, title, tags, base, answer.candidates(), seen);
                if (!judged.safe().isEmpty()) {
                    UUID run = runs.record(clientId, account, marketplace, answer.model(), judged.safe().size(), round, round, anchor);
                    return RewriteOutcome.ended(Status.SUGGESTIONS, judged.safe(), run, title, round, max, Stop.FOUND);
                }
                if (judged.incomplete() || round == max) {
                    UUID run = runs.record(clientId, account, marketplace, answer.model(), 0, round, null, anchor);
                    return RewriteOutcome.ended(Status.NONE_SAFE, List.of(), run, title, round, max,
                            judged.incomplete() ? Stop.CHECK_INCOMPLETE : Stop.ROUNDS_EXHAUSTED);
                }
                previous.add(new RewritePrompt.Round(round, answer.candidates().size(), judged.rejected()));
            }
            throw new IllegalStateException("max-rounds is at least 1, so the first round always ends the press or continues it");
        } catch (PlanNotAllowedException | TrademarkUnavailableException e) {
            // a pack the plan lacks or a catalog that is not served: the candidates cannot be verified, so none is shown
            log.info("Rewrite suggestions not available: {}", e.getClass().getSimpleName());
            return round == 0 ? RewriteOutcome.of(Status.UNAVAILABLE, title)
                    : RewriteOutcome.ended(Status.UNAVAILABLE, List.of(), null, title, round, max, Stop.UNAVAILABLE);
        }
    }

    /** What the model proposed and the model that answered (from the cache or a provider, ADR-036), before any check. */
    private record Answer(List<RewritePrompt.Raw> candidates, String model) {
    }

    /** One call through the shared client, or null when it could not run or its answer is not the JSON the schema describes. */
    private Answer ask(String marketplace, String title, List<IndexedTag> tags, List<Problem> problems,
                       List<RewritePrompt.Round> previous, int round) {
        List<String> tagTexts = tags.stream().map(IndexedTag::text).toList();
        try {
            LlmClient.Result result = client.complete(new LlmClient.Call(round == 1 ? PURPOSE : REPAIR_PURPOSE,
                    RewritePrompt.request(mapper, properties, marketplace, title, tagTexts, problems, previous, round), DATA));
            List<RewritePrompt.Raw> parsed = RewritePrompt.parse(mapper, result.text());
            if (parsed == null) {
                log.info("Rewrite suggestions: the model's answer was not the expected JSON (round {})", round);
                return null;
            }
            return new Answer(parsed, result.model());
        } catch (LlmUnavailableException e) {
            // the message never carries listing text (ADR-036); the reason is enough to diagnose
            log.info("Rewrite suggestions: the model could not be asked ({}, round {})", e.reason(), round);
            return null;
        }
    }

    /** The findings the call is about: every ERROR of the rules and every trademark finding, with the seller's own text. */
    private static List<Problem> problems(Evaluation base) {
        List<Problem> out = new ArrayList<>();
        for (Finding finding : base.errors()) {
            out.add(new Problem(finding.ruleId(), finding.field() == null ? "" : finding.field(), cut(finding.value(), MAX_VALUE_CHARS),
                    finding.message()));
        }
        for (TrademarkFinding finding : base.trademark().findings()) {
            out.add(new Problem(finding.ruleId(), finding.evidence().field() == null ? "" : finding.evidence().field(),
                    cut(finding.evidence().snippet(), MAX_VALUE_CHARS), finding.message()));
        }
        return out;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** What one round's candidates came to: the ones that passed, why each other one did not, and whether a check could not finish. */
    private record Judged(List<RewriteCandidate> safe, List<RewritePrompt.Rejected> rejected, boolean incomplete) {
    }

    private Judged judge(UUID clientId, String marketplace, ObjectNode listing, String title, List<IndexedTag> tags, Evaluation base,
                         List<RewritePrompt.Raw> raw, Set<String> seen) {
        List<RewriteCandidate> safe = new ArrayList<>();
        List<RewritePrompt.Rejected> rejected = new ArrayList<>();
        boolean incomplete = false;
        int number = 0;
        for (RewritePrompt.Raw candidate : raw) {
            number++;
            Verdict verdict = verify(clientId, marketplace, listing, title, tags, base, candidate, safe.size() + 1, seen);
            if (verdict.safe() != null) {
                safe.add(verdict.safe());
            } else {
                rejected.add(new RewritePrompt.Rejected(number, verdict.withhold() ? null : candidate, changed(candidate),
                        verdict.reasons()));
                incomplete |= verdict.incomplete();
            }
        }
        return new Judged(safe, rejected, incomplete);
    }

    /** {@code title} and {@code tags[i]} for what a candidate would change. Names a place, never text. */
    private static List<String> changed(RewritePrompt.Raw candidate) {
        List<String> out = new ArrayList<>();
        if (candidate.title() != null) {
            out.add("title");
        }
        candidate.tags().forEach(tag -> out.add("tags[" + tag.index() + "]"));
        return out;
    }

    /**
     * The verdict on one candidate: {@code safe} when it passed every check, otherwise why not. {@code withhold} is true when the
     * candidate added a registered mark: the model is then told that, and not what it wrote. {@code incomplete} is true when a rule or
     * the trademark check could not finish.
     */
    private record Verdict(RewriteCandidate safe, List<Reason> reasons, boolean withhold, boolean incomplete) {

        static Verdict passed(RewriteCandidate candidate) {
            return new Verdict(candidate, List.of(), false, false);
        }

        static Verdict shape(String field, String detail) {
            return new Verdict(null, List.of(new Reason("shape", "", field, detail)), false, false);
        }
    }

    private Verdict verify(UUID clientId, String marketplace, ObjectNode listing, String title, List<IndexedTag> tags,
                           Evaluation base, RewritePrompt.Raw raw, int number, Set<String> seen) {
        String newTitle = null;
        if (raw.title() != null) {
            newTitle = raw.title().strip();
            String problem = titleProblem(newTitle, title);
            if (problem != null) {
                return Verdict.shape("title", problem);
            }
        }
        ObjectNode copy = listing.deepCopy();
        if (newTitle != null) {
            copy.put("title", newTitle);
        }
        List<RewriteCandidate.TagChange> changes = new ArrayList<>();
        Set<Integer> changed = new HashSet<>();
        for (RewritePrompt.Raw.Tag tag : raw.tags()) {
            String problem = tagProblem(tags, tag, changed);
            if (problem != null) {
                return Verdict.shape("tags[" + tag.index() + "]", problem);
            }
            changes.add(applyTag(copy, tags, tag));
        }
        if (newTitle == null && changes.isEmpty()) {
            return Verdict.shape("", "the candidate changes nothing");
        }
        if (!seen.add(String.valueOf(newTitle) + "\n" + changes)) {
            return Verdict.shape("", "the same proposal as an earlier candidate");
        }
        Evaluation judged = evaluator.evaluate(clientId, marketplace, copy);
        if (accepted(base, judged)) {
            return Verdict.passed(new RewriteCandidate(number, newTitle, changes));
        }
        return rejection(base, judged);
    }

    /** Why the checks did not accept a candidate: the steps that failed, in the words of the checks. Never the name of a mark. */
    private static Verdict rejection(Evaluation base, Evaluation judged) {
        List<Reason> reasons = new ArrayList<>();
        if (!judged.rules().complete()) {
            reasons.add(new Reason("listing_rule", "", "", "a rule could not run"));
        }
        if (judged.trademark().completeness() != Completeness.COMPLETE) {
            reasons.add(new Reason("trademark", "", "", "the trademark check could not complete"));
        }
        for (Finding finding : judged.errors()) {
            reasons.add(new Reason("listing_rule", finding.ruleId(), finding.field() == null ? "" : finding.field(),
                    cut(finding.message(), MAX_FEEDBACK_CHARS)));
        }
        Set<String> known = base.trademarkSignatures();
        Set<String> added = new LinkedHashSet<>();
        for (TrademarkFinding finding : judged.trademark().findings()) {
            if (!known.contains(Evaluation.signature(finding))) {
                String field = finding.evidence().field() == null ? "" : finding.evidence().field().replaceAll("\\[\\d+]", "");
                if (added.add(finding.ruleId() + "|" + field)) {
                    reasons.add(new Reason("trademark", finding.ruleId(), field, "added a registered mark"));
                }
            }
        }
        boolean withhold = !added.isEmpty();
        if (reasons.isEmpty()) {
            reasons.add(new Reason("progress", "", "", "the candidate does not reduce the ERROR and trademark findings: "
                    + base.problems() + " before, " + judged.problems() + " after"));
        }
        return new Verdict(null, reasons, withhold, !judged.complete());
    }

    /** The rules and the trademark check agree: complete, no ERROR, no new trademark finding, and fewer problems than before. */
    static boolean accepted(Evaluation base, Evaluation judged) {
        return judged.complete() && judged.errors().isEmpty()
                && base.trademarkSignatures().containsAll(judged.trademarkSignatures())
                && judged.problems() < base.problems();
    }

    /** Null when the title may go to the checks, otherwise the fixed phrase that says why not. */
    private static String titleProblem(String candidate, String original) {
        if (candidate.isEmpty()) {
            return "the title is empty";
        }
        if (!oneLine(candidate)) {
            return "the title has several lines";
        }
        if (candidate.equals(original)) {
            return "the title is the same as the current one";
        }
        return wordProblem(candidate, original);
    }

    /** No more words than the original, and no word repeated more than twice unless the original already repeated it. */
    static boolean notLongerOrStuffed(String candidate, String original) {
        return wordProblem(candidate, original) == null;
    }

    private static String wordProblem(String candidate, String original) {
        List<String> now = words(candidate);
        List<String> before = words(original);
        if (now.isEmpty()) {
            return "the title has no words";
        }
        if (now.size() > before.size()) {
            return "the title has more words than the original";
        }
        Map<String, Integer> was = counts(before);
        for (Map.Entry<String, Integer> entry : counts(now).entrySet()) {
            if (entry.getValue() > Math.max(MAX_REPEAT, was.getOrDefault(entry.getKey(), 0))) {
                return "a word is repeated more than twice";
            }
        }
        return null;
    }

    /** Null when the tag change may go to the checks, otherwise the fixed phrase that says why not. */
    private static String tagProblem(List<IndexedTag> tags, RewritePrompt.Raw.Tag tag, Set<Integer> changed) {
        if (tag.index() < 0 || tag.index() >= tags.size() || !changed.add(tag.index())) {
            return "the tag index does not exist or is used twice";
        }
        IndexedTag current = tags.get(tag.index());
        String to = tag.to().strip();
        if (!current.text().equals(tag.from().strip())) {
            return "the old tag text does not match the tag at that index";
        }
        if (to.isEmpty() || !oneLine(to) || to.equals(current.text()) || words(to).size() > words(current.text()).size()) {
            return "the replacement tag is empty, on several lines, unchanged or longer";
        }
        for (IndexedTag other : tags) {
            if (other != current && other.text().equalsIgnoreCase(to)) {
                return "the replacement tag repeats another tag";
            }
        }
        return null;
    }

    private static RewriteCandidate.TagChange applyTag(ObjectNode copy, List<IndexedTag> tags, RewritePrompt.Raw.Tag tag) {
        IndexedTag current = tags.get(tag.index());
        String to = tag.to().strip();
        ((ArrayNode) copy.get("tags")).set(current.rawIndex(), to);
        return new RewriteCandidate.TagChange(tag.index(), current.text(), to);
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

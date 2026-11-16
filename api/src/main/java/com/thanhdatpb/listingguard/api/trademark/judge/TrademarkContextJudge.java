package com.thanhdatpb.listingguard.api.trademark.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.api.llm.LlmClient;
import com.thanhdatpb.listingguard.api.llm.LlmDataClass;
import com.thanhdatpb.listingguard.api.llm.LlmProperties;
import com.thanhdatpb.listingguard.api.llm.LlmUnavailableException;
import com.thanhdatpb.listingguard.rag.llm.LlmRequest;
import com.thanhdatpb.listingguard.trademark.CanonicalInput;
import com.thanhdatpb.listingguard.trademark.Evidence;
import com.thanhdatpb.listingguard.trademark.LlmJudgement;
import com.thanhdatpb.listingguard.trademark.LlmJudgement.Judged;
import com.thanhdatpb.listingguard.trademark.LlmJudgement.Verdict;
import com.thanhdatpb.listingguard.trademark.TrademarkCheckInput;
import com.thanhdatpb.listingguard.trademark.TrademarkFinding;
import com.thanhdatpb.listingguard.trademark.TrademarkResult;
import com.thanhdatpb.listingguard.trademark.catalog.CatalogMark;
import com.thanhdatpb.listingguard.trademark.catalog.TrademarkCatalog;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks a language model whether the word a catalog finding matched is a brand or an ordinary word in its sentence (ADR-036 item 8).
 * It sends the mark, its Nice classes, the field and a short span of text around the match, and nothing else of the listing. What
 * the answer may change is decided by {@link LlmJudgement}, which can only lower a MEDIUM catalog finding to LOW.
 *
 * <p>Inactive (the default) it returns the result it was given and adds nothing to the analysis version, so a check is exactly what
 * it was before. It is also inactive when no provider may receive listing text: nothing is sent and the result is not marked
 * incomplete, because that is a setting and not a failure.
 */
@Component
public class TrademarkContextJudge {

    public static final String PURPOSE = "trademark_context_judge";
    public static final String COMPONENT = "llm_judge";

    private static final Logger log = LoggerFactory.getLogger(TrademarkContextJudge.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int REASON_MAX = 200;
    private static final Set<LlmDataClass> LISTING_TEXT = EnumSet.of(LlmDataClass.LISTING_TEXT);

    private final LlmClient client;
    private final LlmProperties.Judge settings;
    private final boolean requested;
    private final JudgePrompt prompt;

    public TrademarkContextJudge(LlmClient client, LlmProperties properties) {
        this.client = client;
        this.settings = properties.judge();
        this.requested = properties.enabled() && settings.enabled();
        this.prompt = requested ? JudgePrompt.load() : null;
        if (requested && !client.canServe(LISTING_TEXT)) {
            log.warn("listing-guard.llm.judge.enabled is true but no LLM provider may receive listing text "
                    + "(listing-text-allowed is false for all): the judge is inactive and sends nothing");
        }
    }

    /** A judge that never does anything. */
    public static TrademarkContextJudge off() {
        return new TrademarkContextJudge(LlmClient.disabled(Clock.systemUTC()),
                new LlmProperties(false, List.of(), new LlmProperties.Cache(1, Duration.ofHours(1)),
                        new LlmProperties.Judge(false, 8, 160, 600, Duration.ofSeconds(20))));
    }

    /** True when a check is judged: the judge is on and at least one provider may receive listing text. */
    public boolean active() {
        return requested && client.canServe(LISTING_TEXT);
    }

    /**
     * The fingerprint component of the analysis version (ADR-036 item 9): prompt version and hash, the models in the order they are
     * tried, and the settings that change what is sent. Empty when the judge is not active, so the fingerprint is the old one.
     */
    public Map<String, String> fingerprintComponents() {
        if (!active()) {
            return Map.of();
        }
        String models = String.join(",", client.modelIds(LISTING_TEXT));
        return Map.of(COMPONENT, prompt.identity() + ";models=" + models + ";max=" + settings.maxPerCheck() + ";snippet="
                + settings.snippetChars());
    }

    /**
     * @param catalog the catalog the scan read, for the Nice classes of an exact or phrase match; may be null
     * @return the result as it was, or with findings lowered and, when the step could not run, INCOMPLETE
     */
    public TrademarkResult judge(TrademarkCheckInput input, TrademarkResult result, TrademarkCatalog catalog) {
        if (!active()) {
            return result;
        }
        List<Integer> eligible = LlmJudgement.eligibleIndexes(result);
        if (eligible.isEmpty()) {
            return result;
        }
        CanonicalInput canonical = CanonicalInput.of(input);
        Map<Integer, Judged> verdicts = new LinkedHashMap<>();
        boolean skipped = false;
        int sent = 0;
        for (int index : eligible) {
            if (sent >= settings.maxPerCheck()) {
                break;
            }
            sent++;
            Evidence evidence = result.findings().get(index).evidence();
            String fieldText = canonical.fields().stream().filter(field -> field.name().equals(evidence.field()))
                    .map(CanonicalInput.Field::text).findFirst().orElse("");
            if (evidence.start() < 0 || evidence.end() > fieldText.length() || evidence.start() >= evidence.end()) {
                continue;
            }
            Set<LlmDataClass> data = EnumSet.of(LlmDataClass.ofField(evidence.field()));
            String mark = clean(evidence.matchedMark() == null ? evidence.snippet() : evidence.matchedMark());
            LlmRequest request = new LlmRequest(prompt.system(),
                    prompt.render(mark, classes(evidence, catalog), evidence.field(),
                            context(fieldText, evidence.start(), evidence.end(), settings.snippetChars())),
                    prompt.schema(), settings.maxOutputTokens(), settings.timeout());
            try {
                verdicts.put(index, parse(client.complete(new LlmClient.Call(PURPOSE, request, data)).text()));
            } catch (LlmUnavailableException e) {
                skipped = true;
                if (e.reason() != LlmUnavailableException.Reason.NO_ELIGIBLE_PROVIDER) {
                    break;
                }
            }
        }
        return LlmJudgement.apply(result, verdicts, skipped);
    }

    /** The text around the match, the match between {@code [[} and {@code ]]}, cut at {@code chars} characters on each side. */
    static String context(String text, int start, int end, int chars) {
        int from = Math.max(0, start - chars);
        int to = Math.min(text.length(), end + chars);
        if (from > 0 && from < text.length() && Character.isLowSurrogate(text.charAt(from))) {
            from++;
        }
        if (to < text.length() && to > 0 && Character.isHighSurrogate(text.charAt(to - 1))) {
            to--;
        }
        return (from > 0 ? "..." : "") + text.substring(from, start) + "[[" + text.substring(start, end) + "]]"
                + text.substring(end, to) + (to < text.length() ? "..." : "");
    }

    private static String classes(Evidence evidence, TrademarkCatalog catalog) {
        if (catalog == null || evidence.normalizedToken() == null) {
            return "unknown";
        }
        try {
            CatalogMark mark = catalog.findExact(List.of(evidence.normalizedToken())).get(evidence.normalizedToken());
            if (mark == null || mark.niceClasses().isEmpty()) {
                return "unknown";
            }
            return mark.niceClasses().stream().map(String::valueOf).collect(Collectors.joining(", "));
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private static String clean(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /**
     * The answer must be exactly {@code {"verdict": ..., "reason": ...}} and nothing else (ADR-036 item 8). Anything that is not,
     * is {@code unsure}: a fence, a missing or extra member, an unknown verdict, an empty reason for "likely_descriptive", a reason
     * longer than 200 characters.
     */
    static Judged parse(String answer) {
        if (answer == null) {
            return Judged.UNSURE;
        }
        String text = answer.strip();
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return Judged.UNSURE;
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (IOException e) {
            return Judged.UNSURE;
        }
        if (root == null || !root.isObject() || root.size() != 2 || !root.path("verdict").isTextual()
                || !root.path("reason").isTextual()) {
            return Judged.UNSURE;
        }
        String reason = clean(root.path("reason").asText());
        if (reason.length() > REASON_MAX) {
            return Judged.UNSURE;
        }
        return switch (root.path("verdict").asText()) {
            case "likely_descriptive" -> reason.isEmpty() ? Judged.UNSURE : new Judged(Verdict.LIKELY_DESCRIPTIVE, reason);
            case "likely_mark_use" -> new Judged(Verdict.LIKELY_MARK_USE, reason);
            default -> Judged.UNSURE;
        };
    }
}

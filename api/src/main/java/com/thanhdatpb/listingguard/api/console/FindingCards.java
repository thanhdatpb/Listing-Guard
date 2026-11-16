package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.console.bulk.BulkResult;
import com.thanhdatpb.listingguard.api.console.editor.EditorResult;
import com.thanhdatpb.listingguard.api.edit.ListingEditResult;
import com.thanhdatpb.listingguard.api.feedback.FeedbackTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Turns the finding records of the editor, of a bulk row and of an edit comparison into one {@link FindingCard}, so every page prints
 * a finding with the same markup (ADR-041). It decides nothing about a finding: the level, the message, the suggestion and the fix
 * are what the check returned; it only adds the words the card needs in the language of the page (the level pill, what the rule
 * checks, where its threshold comes from). It is the bean {@code findingCards}, which a template reaches as
 * {@code ${@findingCards...}}.
 */
@Component("findingCards")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class FindingCards {

    private static final String WATCHLIST_RULE = "watchlist_match";

    private final ConsoleMessages messages;
    private final FindingSources sources;

    public FindingCards(ConsoleMessages messages, FindingSources sources) {
        this.messages = messages;
        this.sources = sources;
    }

    /** A group of cards under a heading: the label of the level and the cards of that level, in the order the page shows them. */
    public record Group(String label, String level, List<FindingCard> cards) {
    }

    // ---- the editor

    /** The listing-rule findings of an editor result by level: errors, warnings, information. */
    public List<Group> editorRules(EditorResult result, String marketplace) {
        List<Group> groups = new ArrayList<>();
        for (EditorResult.Group<EditorResult.RuleItem> group : result.ruleGroups()) {
            List<FindingCard> cards = new ArrayList<>();
            for (EditorResult.RuleItem item : group.items()) {
                FindingCard.Fix fix = item.fix() == null ? null
                        : new FindingCard.Fix(item.fix().action(), item.fix().description(), item.fix().proposedValue());
                cards.add(card("rules", group.css(), item.ruleId(), item.field(), item.message(), item.suggestion(), null,
                        sources.listingRule(marketplace, item.ruleId()), evidence(item.evidence()), fix, item.fixId(), item.value(),
                        null, null, messages.ruleExplanation(item.ruleId()), item.target()));
            }
            groups.add(new Group(group.label(), group.css(), List.copyOf(cards)));
        }
        return List.copyOf(groups);
    }

    /** The trademark findings of an editor result by risk, highest first. */
    public List<Group> editorTrademark(EditorResult result) {
        List<Group> groups = new ArrayList<>();
        for (EditorResult.Group<EditorResult.TrademarkItem> group : result.trademark().groups()) {
            List<FindingCard> cards = new ArrayList<>();
            for (EditorResult.TrademarkItem item : group.items()) {
                cards.add(card("trademark", group.css(), item.ruleId(), item.field(), item.message(), item.suggestion(),
                        item.mark() == null ? null : messages.get("bulk.item.catalogmark", item.mark()),
                        origin(item.ruleId(), item.mark() != null, null), evidence(item.evidence()), null, null, null,
                        item.llmReason(), null, messages.ruleExplanation(item.ruleId()), item.target()));
            }
            groups.add(new Group(group.label(), group.css(), List.copyOf(cards)));
        }
        return List.copyOf(groups);
    }

    // ---- a bulk row

    /**
     * The findings of one check of a bulk row. {@code kind} is {@code rules}, {@code trademark} or {@code variation}. The items carry
     * their own explanation, with the LLM label of a trademark finding already in it (ADR-036), so the card has no separate LLM line.
     */
    public List<FindingCard> bulk(List<BulkResult.Item> items, String kind, String marketplace) {
        List<FindingCard> cards = new ArrayList<>();
        for (BulkResult.Item item : items) {
            String source = "trademark".equals(kind) ? origin(item.ruleId(), item.detail() != null, null)
                    : "rules".equals(kind) ? sources.listingRule(marketplace, item.ruleId()) : null;
            FindingCard.Fix fix = item.fix() == null ? null : new FindingCard.Fix(null, item.fix(), item.fixValue());
            cards.add(card(kind, item.level(), item.ruleId(), item.field(), item.message(), item.suggestion(), item.detail(), source,
                    null, fix, null, null, null, null, item.explanation(), item.target()));
        }
        return List.copyOf(cards);
    }

    // ---- an edit comparison

    /**
     * One finding of an edit comparison. The finding after the edit is the one shown; a finding that is gone is shown as resolved (a
     * success pill, no red diamond), a finding that changed carries the message it had before. {@code marketplace} is the code.
     */
    public FindingCard compared(ListingEditResult.ComparedFinding item, String marketplace) {
        Map<String, Object> shown = item.after() != null ? item.after() : item.before();
        String kind = kindOf(item.pack());
        String ruleId = text(shown, "ruleId");
        String mark = text(shown, "mark");
        String oldMessage = item.before() == null ? null : text(item.before(), "message");
        String before = item.after() != null && oldMessage != null && !Objects.equals(oldMessage, text(item.after(), "message"))
                ? oldMessage : null;
        String source = "trademark".equals(kind) ? origin(ruleId, mark != null, text(shown, "source"))
                : "rules".equals(kind) ? sources.listingRule(marketplace, ruleId) : null;
        FindingCard card = card(kind, levelOf(kind, shown), ruleId, text(shown, "field"), text(shown, "message"),
                text(shown, "suggestion"), mark == null ? null : messages.get("bulk.item.catalogmark", mark), source, null, null,
                null, text(shown, "value"), null, before, messages.ruleExplanation(ruleId), item.feedbackTarget());
        if (item.after() != null) {
            return card;
        }
        return new FindingCard(card.kind(), card.level(), messages.get("finding.resolved"), "clean", false, card.ruleId(),
                card.field(), card.message(), card.explanation(), card.suggestion(), card.detail(), card.sourceLine(), null, null,
                null, card.value(), null, null, card.target());
    }

    // ---- a stored result opened from the history

    /**
     * One finding of a stored check as the history reads it back: the API's own text, the value or span it was about, and the address of
     * its feedback. {@code level} is {@code error}, {@code warning}, {@code info}, {@code high}, {@code medium} or {@code low};
     * {@code mark} is the catalog mark of a trademark finding and {@code source} its {@code CATALOG}, {@code CONTEXT} or
     * {@code WATCHLIST}, both null for any other pack.
     */
    public record Stored(String ruleId, String level, String field, String message, String suggestion, String value, String mark,
                         String source, FindingCard.Fix fix, FeedbackTarget target) {
    }

    /** The findings of a stored check of {@code pack}, as cards in the order they were stored; the history page and the editor share them. */
    public List<FindingCard> history(String pack, String marketplace, List<Stored> findings) {
        String kind = kindOf(pack);
        List<FindingCard> cards = new ArrayList<>();
        for (Stored item : findings) {
            String source = "trademark".equals(kind) ? origin(item.ruleId(), item.mark() != null, item.source())
                    : "rules".equals(kind) ? sources.listingRule(marketplace, item.ruleId()) : null;
            cards.add(card(kind, item.level(), item.ruleId(), item.field(), item.message(), item.suggestion(),
                    item.mark() == null ? null : messages.get("bulk.item.catalogmark", item.mark()), source, null, item.fix(), null,
                    item.value(), null, null, messages.ruleExplanation(item.ruleId()), item.target()));
        }
        return List.copyOf(cards);
    }

    // ---- building blocks

    private FindingCard card(String kind, String level, String ruleId, String field, String message, String suggestion, String detail,
                             String sourceLine, FindingCard.Evidence evidence, FindingCard.Fix fix, Integer fixId, String value,
                             String llmReason, String before, String explanation, FeedbackTarget target) {
        boolean trademark = "trademark".equals(kind);
        String label = levelLabel(level);
        return new FindingCard(kind, level, trademark ? messages.get("finding.trademark.pill", label) : label, tone(kind, level),
                "error".equals(level) || (trademark && "high".equals(level)), ruleId, field, message, explanation, suggestion,
                detail, sourceLine, evidence, fix, fixId, value, llmReason, before, target);
    }

    private static String tone(String kind, String level) {
        if ("trademark".equals(kind)) {
            return "trademark";
        }
        return switch (level) {
            case "error" -> "error";
            case "warning" -> "warn";
            default -> "";
        };
    }

    private String levelLabel(String level) {
        return switch (level) {
            case "error" -> messages.get("bulk.level.error");
            case "warning" -> messages.get("bulk.level.warning");
            case "info" -> messages.get("bulk.level.info");
            case "high" -> messages.get("bulk.level.high");
            case "medium" -> messages.get("bulk.level.medium");
            case "low" -> messages.get("bulk.level.low");
            default -> level;
        };
    }

    /** Where a trademark finding comes from: the client's watchlist, a catalog mark, or a context rule. */
    private String origin(String ruleId, boolean hasMark, String source) {
        if (WATCHLIST_RULE.equals(ruleId) || "WATCHLIST".equals(source)) {
            return messages.get("finding.origin.watchlist");
        }
        return hasMark ? messages.get("finding.origin.catalog") : messages.get("finding.origin.context");
    }

    private static FindingCard.Evidence evidence(EditorResult.Evidence evidence) {
        return evidence == null ? null : new FindingCard.Evidence(evidence.before(), evidence.match(), evidence.after(),
                evidence.cutBefore(), evidence.cutAfter());
    }

    private static String kindOf(String pack) {
        if (pack == null) {
            return "rules";
        }
        return switch (pack) {
            case "trademark-risk" -> "trademark";
            case "variation-checks" -> "variation";
            default -> "rules";
        };
    }

    private static String levelOf(String kind, Map<String, Object> finding) {
        String raw = text(finding, "trademark".equals(kind) ? "risk" : "severity");
        return raw == null ? "info" : raw.toLowerCase(Locale.ROOT);
    }

    private static String text(Map<String, Object> finding, String key) {
        Object value = finding == null ? null : finding.get(key);
        return value == null ? null : String.valueOf(value);
    }
}

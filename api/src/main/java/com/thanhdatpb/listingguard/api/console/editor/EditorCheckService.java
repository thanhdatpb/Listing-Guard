package com.thanhdatpb.listingguard.api.console.editor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.check.NormalizerRegistry;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.core.listing.Listing;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Runs one press of "Check" for the console editor (ADR-014, part B3): the listing rules through the detailed path (verified
 * fixes and readiness, whatever {@code listing-guard.fixes.enabled} says) and the trademark check, then reduces both to an
 * {@link EditorResult}.
 *
 * <p>The two checks are independent. A plan without a pack, a used-up quota or a trademark catalog that is not loaded stops
 * only the check it concerns, and the page says so in that check's panel; the other result is still shown. Both are the
 * services the API uses, so cache identity, retention and quota counting are those of the API and nothing is stored beyond
 * what the API stores (never the listing text).
 *
 * <p>The sentences of the panels are the console's own and come from its messages, in the language of the page (ADR-028). What
 * a finding says (message, suggestion, fix) is the API's text and passes through unchanged.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class EditorCheckService {

    private static final List<Severity> SEVERITY_ORDER = List.of(Severity.ERROR, Severity.WARNING, Severity.INFO);
    private static final List<RiskLevel> RISK_ORDER = List.of(RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW);

    private final CheckService checks;
    private final TrademarkCheckService trademarks;
    private final TrademarkRulesets trademarkRulesets;
    private final NormalizerRegistry normalizers;
    private final JdbcTemplate jdbc;
    private final ConsoleMessages messages;

    public EditorCheckService(CheckService checks, TrademarkCheckService trademarks, TrademarkRulesets trademarkRulesets,
                              NormalizerRegistry normalizers, JdbcTemplate jdbc, ConsoleMessages messages) {
        this.checks = checks;
        this.trademarks = trademarks;
        this.trademarkRulesets = trademarkRulesets;
        this.normalizers = normalizers;
        this.jdbc = jdbc;
        this.messages = messages;
    }

    public EditorResult check(UUID clientId, EditorMarketplace marketplace, ObjectNode listingJson) {
        CheckRequest request = new CheckRequest(marketplace.code(), listingJson);
        Listing listing = normalizers.get(marketplace.code()).normalize(listingJson);
        Rules rules = rules(clientId, request);
        EditorResult.Trademark trademark = trademark(clientId, request, listing);
        return new EditorResult(rules.readiness(), trademark, rules.groups(), rules.ran(), rules.requiredFields());
    }

    private record Rules(EditorResult.Readiness readiness, List<EditorResult.Group<EditorResult.RuleItem>> groups, boolean ran,
                         boolean requiredFields) {
    }

    private Rules rules(UUID clientId, CheckRequest request) {
        CheckResult result;
        try {
            result = checks.checkDetailed(clientId, request);
        } catch (QuotaExceededException e) {
            return notRun(messages.get("editor.rules.quota", e.used(), e.quota(), EditorCost.resetDate(e.resetAt())));
        } catch (PlanNotAllowedException e) {
            return notRun(messages.get("editor.rules.plan", e.plan()));
        }
        boolean required = result.findings().stream().anyMatch(finding -> "required_fields".equals(finding.ruleId()));
        return new Rules(readiness(result), ruleGroups(result), true, required);
    }

    private Rules notRun(String reason) {
        String notScored = messages.get("editor.readiness.notscored");
        return new Rules(new EditorResult.Readiness(EditorResult.ReadinessKind.NOT_RUN, null, notScored, notScored,
                reason, null), List.of(), false, false);
    }

    private EditorResult.Readiness readiness(CheckResult result) {
        CheckResult.ReadinessView view = result.readiness();
        String notScored = messages.get("editor.readiness.notscored");
        String only = messages.get("editor.readiness.only");
        if (view == null) {
            return new EditorResult.Readiness(EditorResult.ReadinessKind.INCOMPLETE, null, notScored, notScored,
                    messages.get("editor.readiness.none") + " " + only, null);
        }
        if ("INCOMPLETE".equals(view.status()) || view.score() == null) {
            return new EditorResult.Readiness(EditorResult.ReadinessKind.INCOMPLETE, null, notScored, notScored,
                    messages.get("editor.readiness.incomplete") + " " + only, view.version());
        }
        String big = String.valueOf(view.score());
        if (view.ready()) {
            return new EditorResult.Readiness(EditorResult.ReadinessKind.READY, view.score(), big,
                    messages.get("editor.readiness.ready.headline"),
                    messages.get("editor.readiness.ready.detail") + " " + only, view.version());
        }
        return new EditorResult.Readiness(EditorResult.ReadinessKind.NOT_READY, view.score(), big,
                messages.get("editor.readiness.notready.headline"),
                messages.get("editor.readiness.notready.detail") + " " + only, view.version());
    }

    private List<EditorResult.Group<EditorResult.RuleItem>> ruleGroups(CheckResult result) {
        List<EditorResult.Group<EditorResult.RuleItem>> groups = new ArrayList<>();
        int nextFixId = 0;
        for (Severity severity : SEVERITY_ORDER) {
            List<EditorResult.RuleItem> items = new ArrayList<>();
            for (CheckResult.FindingView finding : result.findings()) {
                if (finding.severity() == severity) {
                    items.add(ruleItem(finding, finding.fix() == null ? null : nextFixId++));
                }
            }
            if (!items.isEmpty()) {
                groups.add(new EditorResult.Group<>(label(severity), severity.name().toLowerCase(Locale.ROOT), items));
            }
        }
        return groups;
    }

    private String label(Severity severity) {
        return messages.get(switch (severity) {
            case ERROR -> "editor.severity.error";
            case WARNING -> "editor.severity.warning";
            case INFO -> "editor.severity.info";
        });
    }

    private static EditorResult.RuleItem ruleItem(CheckResult.FindingView finding, Integer fixId) {
        CheckResult.FixView fix = finding.fix();
        return new EditorResult.RuleItem(finding.ruleId(), finding.field(), finding.message(), finding.suggestion(),
                finding.value(), EditorEvidence.ofValue(finding.value()),
                fix == null ? null : new EditorResult.Fix(fix.action().name(), fix.description(), fix.proposedValue()), fixId);
    }

    private EditorResult.Trademark trademark(UUID clientId, CheckRequest request, Listing listing) {
        String disclaimer = trademarkRulesets.ruleset().disclaimer();
        try {
            TrademarkCheckResult result = trademarks.check(clientId, request);
            return fromResult(result, listing);
        } catch (TrademarkUnavailableException e) {
            return panel(EditorResult.TrademarkKind.UNAVAILABLE, messages.get("editor.trademark.unavailable.big"),
                    messages.get("editor.trademark.unavailable.headline"), messages.get("editor.trademark.unavailable.detail"),
                    null, disclaimer);
        } catch (PlanNotAllowedException e) {
            return panel(EditorResult.TrademarkKind.NOT_IN_PLAN, messages.get("editor.trademark.notchecked.big"),
                    messages.get("editor.trademark.notchecked.headline"), messages.get("editor.trademark.plan", e.plan()),
                    upgradeHint(e.pack()), disclaimer);
        } catch (QuotaExceededException e) {
            return panel(EditorResult.TrademarkKind.QUOTA_EXCEEDED, messages.get("editor.trademark.notchecked.big"),
                    messages.get("editor.trademark.notchecked.headline"),
                    messages.get("editor.rules.quota", e.used(), e.quota(), EditorCost.resetDate(e.resetAt())),
                    null, disclaimer);
        }
    }

    private EditorResult.Trademark fromResult(TrademarkCheckResult result, Listing listing) {
        List<EditorResult.Group<EditorResult.TrademarkItem>> groups = trademarkGroups(result, listing);
        String disclaimer = result.disclaimer();
        if (result.completeness() == Completeness.COMPLETE) {
            String level = result.riskLevel().name();
            return new EditorResult.Trademark(EditorResult.TrademarkKind.RESULT, level, level,
                    messages.get("editor.trademark.risk", level),
                    result.findings().isEmpty()
                            ? messages.get("editor.trademark.none")
                            : messages.get("editor.trademark.review"),
                    null, disclaimer, groups);
        }
        String known = result.riskLevel() == RiskLevel.UNKNOWN ? messages.get("editor.trademark.unknown")
                : result.riskLevel().name();
        return new EditorResult.Trademark(EditorResult.TrademarkKind.INCOMPLETE,
                result.riskLevel() == RiskLevel.UNKNOWN ? null : result.riskLevel().name(), known,
                messages.get("editor.trademark.incomplete.headline"), messages.get("editor.trademark.incomplete.detail"),
                null, disclaimer, groups);
    }

    private List<EditorResult.Group<EditorResult.TrademarkItem>> trademarkGroups(TrademarkCheckResult result, Listing listing) {
        List<EditorResult.Group<EditorResult.TrademarkItem>> groups = new ArrayList<>();
        for (RiskLevel risk : RISK_ORDER) {
            List<EditorResult.TrademarkItem> items = result.findings().stream().filter(finding -> finding.risk() == risk)
                    .map(finding -> trademarkItem(finding, listing)).toList();
            if (!items.isEmpty()) {
                groups.add(new EditorResult.Group<>(messages.get("editor.trademark.group." + risk.name().toLowerCase(Locale.ROOT)),
                        risk.name().toLowerCase(Locale.ROOT), items));
            }
        }
        return groups;
    }

    private static EditorResult.TrademarkItem trademarkItem(TrademarkCheckResult.FindingView finding, Listing listing) {
        String text = EditorEvidence.fieldText(listing, finding.field());
        return new EditorResult.TrademarkItem(finding.ruleId(), finding.risk().name(), finding.field(),
                finding.matchType().name(), finding.mark(), finding.message(), finding.suggestion(),
                EditorEvidence.ofSpan(text, finding.start(), finding.end(), finding.snippet()));
    }

    private static EditorResult.Trademark panel(EditorResult.TrademarkKind kind, String big, String headline, String detail,
                                                String hint, String disclaimer) {
        return new EditorResult.Trademark(kind, null, big, headline, detail, hint, disclaimer, List.of());
    }

    /** The plans that hold the pack are rows of {@code plan_pack}, so the hint never repeats a plan name written in code. */
    private String upgradeHint(Pack pack) {
        List<String> plans = jdbc.queryForList("SELECT plan_code FROM plan_pack WHERE pack_id = ? ORDER BY plan_code",
                String.class, pack.id());
        if (plans.isEmpty()) {
            return messages.get("editor.upgrade.none");
        }
        return messages.get("editor.upgrade.plans", String.join(", ", plans));
    }
}

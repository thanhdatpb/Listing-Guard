package com.thanhdatpb.listingguard.api.console.bulk;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.console.ConsoleMessages;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Detail;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Item;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Kind;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Row;
import com.thanhdatpb.listingguard.api.console.bulk.BulkResult.Status;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException;
import com.thanhdatpb.listingguard.api.plan.PlanService;
import com.thanhdatpb.listingguard.api.plan.QuotaExceededException;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCatalogRuntime;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.trademark.TrademarkRulesets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException;
import com.thanhdatpb.listingguard.api.variation.VariationCheckRequest;
import com.thanhdatpb.listingguard.api.variation.VariationCheckResult;
import com.thanhdatpb.listingguard.api.variation.VariationCheckService;
import com.thanhdatpb.listingguard.trademark.Completeness;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Runs the checks of a table of listings for the console bulk page (ADR-029). It calls the services the API and {@code batch-check}
 * call, with the client of the signed-in account, and nothing else: {@link CheckService#checkDetailed} for the rules (verified fixes
 * and readiness, like the editor), {@link TrademarkCheckService#check} and, for rows that share a parent SKU,
 * {@link VariationCheckService#check} once per family. Cache identity, retention, plan packs, quota and {@code analysisVersion} are
 * those services'; this class stores nothing, and the listing it is given lives only in the call.
 *
 * <p>The checks are independent, as in the editor and the batch. A plan without the {@code trademark-risk} or {@code variation-checks}
 * pack, a trademark catalog that is not served and a used-up quota each stop only the part they concern, and the row says so. What
 * stops the run is a plan without the {@code listing-rules} pack or a month with no unit left at the start: the caller gets the
 * exception before any row is checked, so the page shows the reason instead of a table of failures. A result already stored is still
 * answered when the quota runs out in the middle.
 */
@Service
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class BulkCheckService {

    private static final Logger log = LoggerFactory.getLogger(BulkCheckService.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern CHILD_FIELD = Pattern.compile("^children\\[(\\d+)]");

    /**
     * One row of the table. {@code preset} is null for a row to check, {@link Status#EMPTY} for a blank row and
     * {@link Status#UNREADABLE} for one whose data could not be read ({@code problem} says why). {@code parentSku}, {@code theme} and
     * {@code attributes} describe the variation family; they are empty for a file read by an adapter.
     */
    public record Input(int line, String sku, String parentSku, String theme, Map<String, String> attributes, ObjectNode listing,
                        Status preset, String problem) {

        public Input {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }

        public static Input of(int line, String sku, String parentSku, String theme, Map<String, String> attributes,
                               ObjectNode listing) {
            return new Input(line, sku, parentSku, theme, attributes, listing, null, null);
        }

        public static Input empty(int line) {
            return new Input(line, null, null, null, null, null, Status.EMPTY, null);
        }

        public static Input unreadable(int line, String sku, String problem) {
            return new Input(line, sku, null, null, null, null, Status.UNREADABLE, problem);
        }
    }

    private final CheckService checks;
    private final TrademarkCheckService trademarks;
    private final VariationCheckService variations;
    private final TrademarkCatalogRuntime trademarkCatalog;
    private final TrademarkRulesets trademarkRulesets;
    private final PlanService plans;
    private final ConsoleMessages messages;

    public BulkCheckService(CheckService checks, TrademarkCheckService trademarks, VariationCheckService variations,
                            TrademarkCatalogRuntime trademarkCatalog, TrademarkRulesets trademarkRulesets, PlanService plans,
                            ConsoleMessages messages) {
        this.checks = checks;
        this.trademarks = trademarks;
        this.variations = variations;
        this.trademarkCatalog = trademarkCatalog;
        this.trademarkRulesets = trademarkRulesets;
        this.plans = plans;
        this.messages = messages;
    }

    /** Where a row stands while the checks run; turned into a {@link Row} at the end. */
    private static final class Acc {
        final Input input;
        BulkResult.Rules rules;
        BulkResult.Trademark trademark;
        Kind variationKind = Kind.NONE;
        String variationNote;
        final List<Item> variationItems = new ArrayList<>();

        Acc(Input input) {
            this.input = input;
        }
    }

    /** What happens to a run as a whole while its rows are checked. */
    private static final class RunState {
        String quotaNote;
    }

    /** One variation family: the request and the rows it came from. {@code rows} are the checked children, in the order of the request. */
    private record Family(VariationCheckRequest request, int parentRow, List<Integer> rows) {

        int rowOfFinding(String field) {
            Matcher matcher = field == null ? null : CHILD_FIELD.matcher(field);
            if (matcher != null && matcher.find()) {
                int child = Integer.parseInt(matcher.group(1));
                if (child < rows.size()) {
                    return rows.get(child);
                }
            }
            return parentRow >= 0 ? parentRow : rows.get(0);
        }

        Set<Integer> allRows() {
            Set<Integer> all = new LinkedHashSet<>();
            if (parentRow >= 0) {
                all.add(parentRow);
            }
            all.addAll(rows);
            return all;
        }
    }

    /**
     * @param header whether the table had a header row (the pasted block then starts with a header line)
     * @throws PlanNotAllowedException if the plan has no {@code listing-rules} pack (only where plans are on, ADR-013)
     * @throws QuotaExceededException  if no unit of the month's quota is left (only where plans are on); nothing was checked
     */
    public BulkResult run(UUID clientId, EditorMarketplace marketplace, List<Input> inputs, boolean header) {
        PlanService.Admission admission = plans.requirePack(clientId, Pack.LISTING_RULES);
        plans.requireQuota(admission);
        String trademarkNote = trademarkNote(clientId);

        List<Acc> accs = new ArrayList<>();
        for (Input input : inputs) {
            accs.add(new Acc(input));
        }
        List<Family> families = families(marketplace, accs);
        String variationNote = families.isEmpty() ? null : variationNote(clientId);

        RunState state = new RunState();
        for (Acc acc : accs) {
            if (acc.input.preset() != null) {
                continue;
            }
            acc.rules = rules(clientId, marketplace, acc.input, state);
            acc.trademark = trademarkNote != null ? BulkResult.Trademark.notRun(trademarkNote)
                    : trademark(clientId, marketplace, acc.input, state);
        }
        for (Family family : families) {
            applyVariation(clientId, family, accs, variationNote, state);
        }

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < accs.size(); i++) {
            rows.add(row(i + 1, accs.get(i)));
        }
        List<String> notes = new ArrayList<>();
        if (trademarkNote != null) {
            notes.add(messages.get("bulk.note.trademark.all", trademarkNote));
        }
        if (variationNote != null) {
            notes.add(messages.get("bulk.note.variation.all", variationNote));
        }
        if (state.quotaNote != null) {
            notes.add(messages.get("bulk.note.quota", state.quotaNote));
        }
        return BulkResult.of(messages, marketplace.code(), header, rows, notes, trademarkRulesets.ruleset().disclaimer());
    }

    private Row row(int index, Acc acc) {
        Input input = acc.input;
        if (input.preset() == Status.EMPTY) {
            return new Row(index, input.line(), null, Status.EMPTY, Status.EMPTY.label(messages), 0, null, "", Detail.empty());
        }
        Detail detail = input.preset() == Status.UNREADABLE ? Detail.unread(input.problem())
                : new Detail(acc.rules, acc.trademark, variation(acc), null);
        BulkAssessment.Assessed assessed = BulkAssessment.of(messages, detail);
        return new Row(index, input.line(), input.sku(), assessed.status(), assessed.status().label(messages), assessed.findings(),
                assessed.main(), assessed.summary(), detail);
    }

    private static BulkResult.Variation variation(Acc acc) {
        return switch (acc.variationKind) {
            case NONE -> BulkResult.Variation.none();
            case NOT_RUN -> BulkResult.Variation.notRun(acc.variationNote);
            default -> new BulkResult.Variation(acc.variationKind, acc.variationNote, List.copyOf(acc.variationItems));
        };
    }

    // ---- the listing rules

    private BulkResult.Rules rules(UUID clientId, EditorMarketplace marketplace, Input input, RunState state) {
        try {
            CheckResult result = checks.checkDetailed(clientId, new CheckRequest(marketplace.code(), input.listing()));
            List<Item> items = new ArrayList<>();
            for (CheckResult.FindingView finding : result.findings()) {
                String level = finding.severity().name().toLowerCase(Locale.ROOT);
                CheckResult.FixView fix = finding.fix();
                items.add(new Item(level, BulkText.level(messages, level), finding.ruleId(), finding.field(), finding.message(),
                        finding.suggestion(), null, messages.ruleExplanation(finding.ruleId()),
                        fix == null ? null : fix.description(), fix == null ? null : fix.proposedValue()));
            }
            String readiness = readiness(result.readiness());
            return new BulkResult.Rules(true, null, readiness, readinessLabel(readiness), score(result.readiness()),
                    List.copyOf(items));
        } catch (QuotaExceededException e) {
            state.quotaNote = state.quotaNote == null ? BulkText.quota(messages, e) : state.quotaNote;
            return BulkResult.Rules.notRun(BulkText.quota(messages, e));
        } catch (PlanNotAllowedException e) {
            return BulkResult.Rules.notRun(BulkText.plan(messages, e));
        } catch (RuntimeException e) {
            // the listing is client data: only the kind of failure and the line are logged
            log.warn("Bulk check: the rule check of line {} failed ({})", input.line(), e.getClass().getSimpleName());
            return BulkResult.Rules.notRun(BulkText.checkFailed(messages));
        }
    }

    private static String readiness(CheckResult.ReadinessView view) {
        if (view == null || "INCOMPLETE".equals(view.status()) || view.score() == null) {
            return "INCOMPLETE";
        }
        return view.ready() ? "READY" : "NOT_READY";
    }

    private String readinessLabel(String readiness) {
        return switch (readiness) {
            case "READY" -> messages.get("bulk.readiness.ready");
            case "NOT_READY" -> messages.get("bulk.readiness.notready");
            default -> messages.get("bulk.readiness.incomplete");
        };
    }

    private static Integer score(CheckResult.ReadinessView view) {
        return view == null ? null : view.score();
    }

    // ---- the trademark check

    /** Why the trademark check will not run for any row, or null when it will. */
    private String trademarkNote(UUID clientId) {
        if (trademarkCatalog.status().state() != TrademarkCatalogRuntime.State.READY) {
            return BulkText.trademarkNotLoaded(messages);
        }
        try {
            plans.requirePack(clientId, Pack.TRADEMARK_RISK);
            return null;
        } catch (PlanNotAllowedException e) {
            return BulkText.plan(messages, e);
        }
    }

    private BulkResult.Trademark trademark(UUID clientId, EditorMarketplace marketplace, Input input, RunState state) {
        try {
            TrademarkCheckResult result = trademarks.check(clientId, new CheckRequest(marketplace.code(), input.listing()));
            List<Item> items = new ArrayList<>();
            for (TrademarkCheckResult.FindingView finding : result.findings()) {
                String level = finding.risk().name().toLowerCase(Locale.ROOT);
                items.add(new Item(level, BulkText.level(messages, level), finding.ruleId(), finding.field(), finding.message(),
                        finding.suggestion(), finding.mark() == null ? null : messages.get("bulk.item.catalogmark", finding.mark()),
                        messages.ruleExplanation(finding.ruleId()), null, null));
            }
            boolean complete = result.completeness() == Completeness.COMPLETE;
            String level = result.riskLevel() == RiskLevel.UNKNOWN || result.riskLevel() == null ? null : result.riskLevel().name();
            return new BulkResult.Trademark(complete ? Kind.RESULT : Kind.INCOMPLETE, level, null, List.copyOf(items));
        } catch (TrademarkUnavailableException e) {
            return BulkResult.Trademark.notRun(BulkText.trademarkNotLoaded(messages));
        } catch (PlanNotAllowedException e) {
            return BulkResult.Trademark.notRun(BulkText.plan(messages, e));
        } catch (QuotaExceededException e) {
            state.quotaNote = state.quotaNote == null ? BulkText.quota(messages, e) : state.quotaNote;
            return BulkResult.Trademark.notRun(BulkText.quota(messages, e));
        } catch (RuntimeException e) {
            log.warn("Bulk check: the trademark check of line {} failed ({})", input.line(), e.getClass().getSimpleName());
            return BulkResult.Trademark.notRun(BulkText.checkFailed(messages));
        }
    }

    // ---- variation families

    /** Why the variation check will not run for any family, or null when it will. */
    private String variationNote(UUID clientId) {
        try {
            plans.requirePack(clientId, Pack.VARIATION_CHECKS);
            return null;
        } catch (PlanNotAllowedException e) {
            return BulkText.plan(messages, e);
        }
    }

    /**
     * The families of the table: the rows with the same parent SKU. For Amazon the parent is the row whose SKU is that value (its
     * title, product type and theme fill the parent object) and, when no row has it, the SKU alone. For Etsy the listing is its own
     * parent and the variation types are the names of the attribute columns. A child enters the request only if the normalizer would
     * keep it (it has a SKU, parent SKU, title or attribute), so {@code children[i]} of a finding counts the same list as {@code rows}.
     */
    private List<Family> families(EditorMarketplace marketplace, List<Acc> accs) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < accs.size(); i++) {
            Input input = accs.get(i).input;
            if (input.preset() == null && present(input.parentSku())) {
                groups.computeIfAbsent(input.parentSku().trim(), key -> new ArrayList<>()).add(i);
            }
        }
        List<Family> families = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> group : groups.entrySet()) {
            String parentSku = group.getKey();
            int parentRow = -1;
            if (marketplace.isAmazon()) {
                for (int j = 0; j < accs.size(); j++) {
                    Input input = accs.get(j).input;
                    if (input.preset() == null && !present(input.parentSku()) && input.sku() != null
                            && parentSku.equals(input.sku().trim())) {
                        parentRow = j;
                        break;
                    }
                }
            }
            ArrayNode children = JSON.arrayNode();
            List<Integer> kept = new ArrayList<>();
            Set<String> properties = new LinkedHashSet<>();
            for (int index : group.getValue()) {
                Input input = accs.get(index).input;
                ObjectNode child = JSON.objectNode();
                if (present(input.sku())) {
                    child.put("sku", input.sku().trim());
                }
                if (marketplace.isAmazon()) {
                    child.put("parent_sku", parentSku);
                }
                String title = input.listing().path("title").asText("");
                if (present(title)) {
                    child.put("title", title);
                }
                if (!input.attributes().isEmpty()) {
                    ObjectNode attributes = child.putObject("attributes");
                    input.attributes().forEach(attributes::put);
                    properties.addAll(input.attributes().keySet());
                }
                if (child.isEmpty()) {
                    continue;
                }
                children.add(child);
                kept.add(index);
            }
            if (kept.isEmpty()) {
                continue;
            }
            VariationCheckRequest request;
            if (marketplace.isAmazon()) {
                request = new VariationCheckRequest(marketplace.code(), parentObject(parentSku, parentRow, kept, accs), null, children);
            } else {
                ArrayNode names = JSON.arrayNode();
                properties.forEach(names::add);
                request = new VariationCheckRequest(marketplace.code(), null, names, children);
            }
            families.add(new Family(request, parentRow, List.copyOf(kept)));
        }
        return families;
    }

    private static ObjectNode parentObject(String parentSku, int parentRow, List<Integer> kept, List<Acc> accs) {
        ObjectNode parent = JSON.objectNode();
        parent.put("sku", parentSku);
        List<Input> sources = new ArrayList<>();
        if (parentRow >= 0) {
            sources.add(accs.get(parentRow).input);
        }
        kept.forEach(index -> sources.add(accs.get(index).input));
        String title = parentRow >= 0 ? accs.get(parentRow).input.listing().path("title").asText("") : "";
        if (present(title)) {
            parent.put("title", title);
        }
        for (Input source : sources) {
            String productType = source.listing().path("product_type").asText("");
            if (present(productType)) {
                parent.put("product_type", productType);
                break;
            }
        }
        for (Input source : sources) {
            if (present(source.theme())) {
                parent.put("variation_theme", source.theme());
                break;
            }
        }
        return parent;
    }

    private void applyVariation(UUID clientId, Family family, List<Acc> accs, String planNote, RunState state) {
        Kind kind;
        String note = null;
        List<VariationCheckResult.FindingView> findings = List.of();
        if (planNote != null) {
            kind = Kind.NOT_RUN;
            note = planNote;
        } else {
            try {
                VariationCheckResult result = variations.check(clientId, family.request());
                kind = result.completeness() == com.thanhdatpb.listingguard.variation.Completeness.COMPLETE ? Kind.RESULT
                        : Kind.INCOMPLETE;
                findings = result.findings();
            } catch (QuotaExceededException e) {
                state.quotaNote = state.quotaNote == null ? BulkText.quota(messages, e) : state.quotaNote;
                kind = Kind.NOT_RUN;
                note = BulkText.quota(messages, e);
            } catch (PlanNotAllowedException e) {
                kind = Kind.NOT_RUN;
                note = BulkText.plan(messages, e);
            } catch (RuntimeException e) {
                log.warn("Bulk check: a variation check failed ({})", e.getClass().getSimpleName());
                kind = Kind.NOT_RUN;
                note = BulkText.checkFailed(messages);
            }
        }
        for (int row : family.allRows()) {
            Acc acc = accs.get(row);
            acc.variationKind = kind;
            acc.variationNote = note;
        }
        for (VariationCheckResult.FindingView finding : findings) {
            String level = finding.severity().name().toLowerCase(Locale.ROOT);
            accs.get(family.rowOfFinding(finding.field())).variationItems.add(new Item(level, BulkText.level(messages, level),
                    finding.ruleId(), finding.field(), finding.message(), finding.suggestion(), null,
                    messages.ruleExplanation(finding.ruleId()), null, null));
        }
    }

    private static boolean present(String text) {
        return text != null && !text.isBlank();
    }
}

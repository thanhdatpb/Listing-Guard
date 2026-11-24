package com.thanhdatpb.listingguard.api.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.thanhdatpb.listingguard.api.check.CheckRequest;
import com.thanhdatpb.listingguard.api.check.CheckResult;
import com.thanhdatpb.listingguard.api.check.CheckService;
import com.thanhdatpb.listingguard.api.plan.Pack;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckRequest;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckResult;
import com.thanhdatpb.listingguard.api.trademark.TrademarkCheckService;
import com.thanhdatpb.listingguard.api.variation.VariationCheckRequest;
import com.thanhdatpb.listingguard.api.variation.VariationCheckResult;
import com.thanhdatpb.listingguard.api.variation.VariationCheckService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Composes existing pack services so their cache, plan, quota and watchlist behavior stays authoritative. */
@Service
public class ListingEditService {
    private final CheckService checks;
    private final TrademarkCheckService trademarks;
    private final VariationCheckService variations;
    private final ObjectMapper mapper;

    public ListingEditService(CheckService checks, TrademarkCheckService trademarks, VariationCheckService variations,
                              ObjectMapper mapper) {
        this.checks = checks;
        this.trademarks = trademarks;
        this.variations = variations;
        this.mapper = mapper;
    }

    public ListingEditResult check(UUID clientId, ListingEditRequest request) {
        JsonNode beforeListing = request.before();
        JsonNode afterListing = request.after();
        CheckRequest beforeRequest = new CheckRequest(request.marketplace(), beforeListing);
        CheckRequest afterRequest = new CheckRequest(request.marketplace(), afterListing);
        CheckResult beforeRules = checks.checkDetailed(clientId, beforeRequest);
        CheckResult afterRules = checks.checkDetailed(clientId, afterRequest);
        TrademarkCheckResult beforeTrademark = trademarks.check(clientId,
                new TrademarkCheckRequest(request.marketplace(), beforeListing, null));
        TrademarkCheckResult afterTrademark = trademarks.check(clientId,
                new TrademarkCheckRequest(request.marketplace(), afterListing, null));
        VariationCheckResult beforeVariation = null;
        VariationCheckResult afterVariation = null;
        if (hasVariationFamily(request.beforeVariations())) {
            beforeVariation = variation(clientId, request.marketplace(), request.beforeVariations());
        }
        if (hasVariationFamily(request.afterVariations())) {
            afterVariation = variation(clientId, request.marketplace(), request.afterVariations());
        }

        return assemble(request, beforeRules, afterRules, beforeTrademark, afterTrademark, beforeVariation, afterVariation,
                true, true);
    }

    public enum PackState { COMPLETE, INCOMPLETE, QUOTA, UNAVAILABLE }

    public record ConsoleComparison(ListingEditResult result, PackState listingRules, PackState trademarkRisk) { }

    /** The console can show a partial comparison without treating missing results as resolved findings. */
    public ConsoleComparison checkConsole(UUID clientId, ListingEditRequest request) {
        CheckRequest before = new CheckRequest(request.marketplace(), request.before());
        CheckRequest after = new CheckRequest(request.marketplace(), request.after());
        CheckResult beforeRules = null;
        CheckResult afterRules = null;
        PackState rulesState;
        try {
            beforeRules = checks.checkDetailed(clientId, before);
            afterRules = checks.checkDetailed(clientId, after);
            rulesState = ready(beforeRules) && ready(afterRules) ? PackState.COMPLETE : PackState.INCOMPLETE;
        } catch (com.thanhdatpb.listingguard.api.plan.QuotaExceededException e) {
            rulesState = PackState.QUOTA;
        } catch (com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException e) {
            rulesState = PackState.UNAVAILABLE;
        }

        TrademarkCheckResult beforeTrademark = null;
        TrademarkCheckResult afterTrademark = null;
        PackState trademarkState;
        try {
            beforeTrademark = trademarks.check(clientId, before);
            afterTrademark = trademarks.check(clientId, after);
            trademarkState = complete(beforeTrademark) && complete(afterTrademark) ? PackState.COMPLETE : PackState.INCOMPLETE;
        } catch (com.thanhdatpb.listingguard.api.plan.QuotaExceededException e) {
            trademarkState = PackState.QUOTA;
        } catch (com.thanhdatpb.listingguard.api.plan.PlanNotAllowedException
                 | com.thanhdatpb.listingguard.api.trademark.TrademarkUnavailableException e) {
            trademarkState = PackState.UNAVAILABLE;
        }
        return new ConsoleComparison(assemble(request, beforeRules, afterRules, beforeTrademark, afterTrademark, null, null,
                rulesState == PackState.COMPLETE, trademarkState == PackState.COMPLETE), rulesState, trademarkState);
    }

    private static boolean ready(CheckResult result) {
        return result.readiness() != null && result.readiness().score() != null;
    }

    private static boolean complete(TrademarkCheckResult result) {
        return result.completeness() == com.thanhdatpb.listingguard.trademark.Completeness.COMPLETE;
    }

    private ListingEditResult assemble(ListingEditRequest request, CheckResult beforeRules, CheckResult afterRules,
                                       TrademarkCheckResult beforeTrademark, TrademarkCheckResult afterTrademark,
                                       VariationCheckResult beforeVariation, VariationCheckResult afterVariation,
                                       boolean compareRules, boolean compareTrademark) {
        List<FindingRef> before = findings(compareRules ? beforeRules : null,
                compareTrademark ? beforeTrademark : null, beforeVariation);
        List<FindingRef> after = findings(compareRules ? afterRules : null,
                compareTrademark ? afterTrademark : null, afterVariation);
        Comparison comparison = compare(before, after);
        return new ListingEditResult(new ListingEditResult.Side(beforeRules, beforeTrademark, beforeVariation),
                new ListingEditResult.Side(afterRules, afterTrademark, afterVariation),
                changedFields(request, request.before(), request.after()), comparison.newFindings(), comparison.modified(),
                comparison.remaining());
    }

    private VariationCheckResult variation(UUID clientId, String marketplace, ListingEditRequest.VariationFamily family) {
        return variations.check(clientId, new VariationCheckRequest(marketplace, family.parent(),
                tree(family.variationProperties()), family.children()));
    }

    private JsonNode tree(Object value) {
        return value == null ? null : mapper.valueToTree(value);
    }

    private boolean hasVariationFamily(ListingEditRequest.VariationFamily family) {
        return family != null && (family.parent() != null || family.children() != null && !family.children().isEmpty());
    }

    private List<FindingRef> findings(CheckResult rules, TrademarkCheckResult trademark, VariationCheckResult variation) {
        List<FindingRef> out = new ArrayList<>();
        for (int i = 0; rules != null && i < rules.findings().size(); i++) {
            out.add(ref(Pack.LISTING_RULES.id(), rules.checkId(), i, mapper.valueToTree(rules.findings().get(i))));
        }
        for (int i = 0; trademark != null && i < trademark.findings().size(); i++) {
            out.add(ref(Pack.TRADEMARK_RISK.id(), trademark.checkId(), i, mapper.valueToTree(trademark.findings().get(i))));
        }
        if (variation != null) {
            for (int i = 0; i < variation.findings().size(); i++) {
                out.add(ref(Pack.VARIATION_CHECKS.id(), variation.checkId(), i, mapper.valueToTree(variation.findings().get(i))));
            }
        }
        return out;
    }

    private FindingRef ref(String pack, UUID id, int index, JsonNode finding) {
        String source = finding.path("source").asText("RULE");
        String rule = finding.path("ruleId").asText();
        String field = finding.path("field").asText("");
        String signature = pack + "|" + source + "|" + rule + "|" + field;
        return new FindingRef(pack, id, index, signature, finding, fingerprint(finding));
    }

    private String fingerprint(JsonNode finding) {
        return finding.toString();
    }

    private Comparison compare(List<FindingRef> before, List<FindingRef> after) {
        Map<String, List<FindingRef>> oldBySignature = group(before);
        Map<String, List<FindingRef>> newBySignature = group(after);
        Set<FindingRef> pairedOld = new HashSet<>();
        Set<FindingRef> pairedNew = new HashSet<>();
        List<ListingEditResult.ComparedFinding> modified = new ArrayList<>();
        List<ListingEditResult.ComparedFinding> remaining = new ArrayList<>();
        for (Map.Entry<String, List<FindingRef>> entry : newBySignature.entrySet()) {
            List<FindingRef> old = oldBySignature.getOrDefault(entry.getKey(), List.of());
            List<FindingRef> now = entry.getValue();
            boolean[] used = new boolean[old.size()];
            for (FindingRef current : now) {
                int exact = -1;
                for (int i = 0; i < old.size(); i++) {
                    if (!used[i] && old.get(i).fingerprint().equals(current.fingerprint())) { exact = i; break; }
                }
                if (exact >= 0) {
                    used[exact] = true;
                    pairedOld.add(old.get(exact)); pairedNew.add(current);
                    remaining.add(compared(old.get(exact), current, current));
                } else {
                    int changed = -1;
                    for (int i = 0; i < old.size(); i++) if (!used[i]) { changed = i; break; }
                    if (changed >= 0) {
                        used[changed] = true;
                        pairedOld.add(old.get(changed)); pairedNew.add(current);
                        modified.add(compared(old.get(changed), current, current));
                    }
                }
            }
        }
        List<ListingEditResult.ComparedFinding> fresh = after.stream().filter(f -> !pairedNew.contains(f))
                .map(f -> compared(null, f, f)).toList();
        before.stream().filter(f -> !pairedOld.contains(f))
                .map(f -> compared(f, null, f)).forEach(modified::add);
        return new Comparison(fresh, modified, remaining);
    }

    private ListingEditResult.ComparedFinding compared(FindingRef before, FindingRef after, FindingRef feedback) {
        String pack = after == null ? before.pack() : after.pack();
        return new ListingEditResult.ComparedFinding(pack, before == null ? null : findingMap(before.finding()),
                after == null ? null : findingMap(after.finding()),
                ListingEditResult.ComparedFinding.target(feedback.pack(), feedback.checkId(), feedback.position()));
    }

    private Map<String, Object> findingMap(JsonNode finding) {
        return mapper.convertValue(finding, new TypeReference<>() { });
    }

    private Map<String, List<FindingRef>> group(List<FindingRef> values) {
        Map<String, List<FindingRef>> grouped = new LinkedHashMap<>();
        values.forEach(value -> grouped.computeIfAbsent(value.signature(), ignored -> new ArrayList<>()).add(value));
        return grouped;
    }

    private List<String> changedFields(ListingEditRequest request, JsonNode before, JsonNode after) {
        List<String> changed = new ArrayList<>();
        diff("", before, after, changed);
        if (request.beforeVariations() != null || request.afterVariations() != null) {
            diff("variations", mapper.valueToTree(request.beforeVariations()), mapper.valueToTree(request.afterVariations()), changed);
        }
        return changed.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private void diff(String path, JsonNode before, JsonNode after, List<String> changed) {
        if (before == null || after == null || before.getNodeType() != after.getNodeType()) {
            changed.add(path.isEmpty() ? "$" : path);
        } else if (before.isObject()) {
            Set<String> keys = new HashSet<>();
            before.fieldNames().forEachRemaining(keys::add);
            after.fieldNames().forEachRemaining(keys::add);
            keys.stream().sorted().forEach(key -> diff(path.isEmpty() ? key : path + "." + key, before.get(key), after.get(key), changed));
        } else if (before.isArray()) {
            if (!before.equals(after)) changed.add(path);
        } else if (!before.equals(after)) {
            changed.add(path);
        }
    }

    private record FindingRef(String pack, UUID checkId, int position, String signature, JsonNode finding, String fingerprint) { }
    private record Comparison(List<ListingEditResult.ComparedFinding> newFindings,
                              List<ListingEditResult.ComparedFinding> modified,
                              List<ListingEditResult.ComparedFinding> remaining) { }
}

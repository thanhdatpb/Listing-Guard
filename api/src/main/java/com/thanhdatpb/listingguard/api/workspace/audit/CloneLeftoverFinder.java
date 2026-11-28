package com.thanhdatpb.listingguard.api.workspace.audit;

import com.thanhdatpb.listingguard.api.workspace.ListingComparison;
import com.thanhdatpb.listingguard.api.workspace.ListingCloner;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import com.thanhdatpb.listingguard.ruleengine.hygiene.FactExtractor;
import com.thanhdatpb.listingguard.ruleengine.hygiene.Mention;
import com.thanhdatpb.listingguard.ruleengine.hygiene.ProductNouns;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a clone still says of its original (ADR-039 §4). The columns of the two listings are paired by {@link ListingComparison#aligned}, the one
 * pairing of ADR-062 §4, so a clone in another account with another template is compared through the same pairs the clone page uses.
 *
 * <p>Two things can be new in a clone: the value of a variation attribute column (a column given a target of kind ATTRIBUTE, such as Size) and its product type or
 * category. When such a value differs from the original's and states a fact ({@code 15oz} for {@code 11oz}, {@code Pack of 4} for {@code Pack of 2},
 * {@code Dad} for {@code Mom}, a product type of another kind of product), the clone's texts are read: a text that still states the original's value
 * and does not state the new one is a leftover, and the finding names both values, the column, the text and the span of the old value. A value no
 * extractor can read (a colour, {@code M}) is not a fact and is never reported. Nothing here reads a text of the original except the two values it
 * compares, and nothing leaves the process.
 */
public final class CloneLeftoverFinder {

    /** One leftover: a text of the clone, the span of the original's value in it, and the sentences. */
    public record Leftover(String field, int start, int end, String value, String message, String suggestion) {
    }

    private record Candidate(String column, String newValue, String oldValue, FactExtractor extractor, String family, Set<String> oldKeys,
                             Set<String> newKeys) {
    }

    private final CloneLeftoverPack pack;
    private final StandardTargets targets;

    public CloneLeftoverFinder(CloneLeftoverPack pack, StandardTargets targets) {
        this.pack = pack;
        this.targets = targets;
    }

    /** @param texts the clone's texts by path ({@code FieldTexts.of}), in the offsets the page marks */
    public List<Leftover> find(Marketplace marketplace, ListingComparison.Alignment alignment, Listing clone, Map<String, String> texts) {
        List<Candidate> candidates = new ArrayList<>();
        Map<String, Boolean> unchanged = new LinkedHashMap<>();
        for (ListingCloner.Pair pair : alignment.pairs()) {
            String target = pair.target();
            if (target == null) {
                continue;
            }
            String now = lines(clone.data().getOrDefault(pair.targetKey(), "")).strip();
            String was = lines(alignment.original().data().getOrDefault(pair.sourceKey(), "")).strip();
            StandardTargets.Kind kind = targets.find(marketplace, target).map(StandardTargets.Target::kind).orElse(null);
            if (!now.isEmpty()) {
                unchanged.merge(target, was.equals(now), Boolean::logicalAnd);
            }
            if (now.isEmpty() || was.isEmpty() || now.equalsIgnoreCase(was)) {
                continue;
            }
            String column = pair.targetHeader() == null || pair.targetHeader().isBlank() ? pair.targetKey() : pair.targetHeader().strip();
            if (kind == StandardTargets.Kind.ATTRIBUTE) {
                for (FactExtractor extractor : List.of(pack.size(), pack.quantity(), pack.recipient())) {
                    attribute(candidates, column, now, was, extractor);
                }
            } else if (target.equals("product_type") || target.equals("category")) {
                productType(candidates, column, now, was);
            }
        }
        List<Leftover> found = new ArrayList<>();
        for (Candidate candidate : candidates) {
            for (Map.Entry<String, String> text : texts.entrySet()) {
                String base = base(text.getKey());
                if (!pack.fields().contains(base)) {
                    continue;
                }
                List<Mention> mentions = candidate.extractor().extract(text.getValue()).stream()
                        .filter(m -> m.family().equals(candidate.family())).toList();
                if (mentions.stream().anyMatch(m -> candidate.newKeys().contains(m.key()))) {
                    continue;
                }
                mentions.stream().filter(m -> candidate.oldKeys().contains(m.key())).findFirst().ifPresent(m -> found.add(leftover(candidate,
                        text.getKey(), m, Boolean.TRUE.equals(unchanged.get(base)))));
            }
        }
        return found;
    }

    private static void attribute(List<Candidate> candidates, String column, String now, String was, FactExtractor extractor) {
        Map<String, Set<String>> before = keysByFamily(extractor.extract(was));
        Map<String, Set<String>> after = keysByFamily(extractor.extract(now));
        for (Map.Entry<String, Set<String>> family : before.entrySet()) {
            Set<String> newKeys = after.get(family.getKey());
            if (newKeys != null && Collections.disjoint(family.getValue(), newKeys)) {
                candidates.add(new Candidate(column, now, was, extractor, family.getKey(), family.getValue(), newKeys));
            }
        }
    }

    private void productType(List<Candidate> candidates, String column, String now, String was) {
        ProductNouns nouns = pack.nouns();
        Set<String> before = nouns.groupsOf(was).orElse(null);
        Set<String> after = nouns.groupsOf(now).orElse(null);
        if (before != null && after != null && Collections.disjoint(before, after)) {
            candidates.add(new Candidate(column, now, was, nouns, ProductNouns.FAMILY, before, after));
        }
    }

    private static Map<String, Set<String>> keysByFamily(List<Mention> mentions) {
        Map<String, Set<String>> families = new LinkedHashMap<>();
        mentions.forEach(m -> families.computeIfAbsent(m.family(), f -> new LinkedHashSet<>()).add(m.key()));
        return families;
    }

    private static Leftover leftover(Candidate candidate, String field, Mention mention, boolean textUnchanged) {
        String what = candidate.extractor() instanceof ProductNouns ? "Loại sản phẩm" : "Cột " + quote(candidate.column());
        String message = what + " của bản nhân bản là " + quote(candidate.newValue()) + " (bản gốc: " + quote(candidate.oldValue()) + ") nhưng "
                + field + " còn ghi " + quote(mention.text()) + ", giá trị của bản gốc"
                + (textUnchanged ? "; chữ này còn giống hệt bản gốc" : "") + ".";
        return new Leftover(field, mention.start(), mention.end(), mention.text(), message,
                "Sửa chỗ này theo giá trị mới của bản nhân bản, hoặc bỏ nếu không còn đúng. Listing Guard không tự sửa.");
    }

    private static String quote(String text) {
        int length = text.codePointCount(0, text.length());
        return "\"" + (length <= 40 ? text : text.substring(0, text.offsetByCodePoints(0, 40)) + "...") + "\"";
    }

    private static String lines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String base(String path) {
        int bracket = path.indexOf('[');
        return bracket < 0 ? path : path.substring(0, bracket);
    }
}

package com.thanhdatpb.listingguard.ruleengine.hygiene;

import com.thanhdatpb.listingguard.core.listing.Listing;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The same fact told two ways inside one listing (ADR-039). The anchor text (the title) and each other text are read with one extractor;
 * for a family that both state, the two are in conflict when they share no value. A text that states a family twice ({@code Mom and Dad})
 * agrees with another that states either value, and a family only one of the two states is not compared: with one witness there is no conflict.
 */
public final class FactConflicts {

    /** @param anchor the anchor's first mention of the family, {@code other} the first one in {@code otherText} */
    public record Conflict(String family, FieldText anchorText, Mention anchor, FieldText otherText, Mention other) {
    }

    private FactConflicts() {
    }

    public static List<Conflict> find(Listing listing, String anchorField, List<String> against, FactExtractor extractor) {
        List<FieldText> anchors = ListingTexts.of(listing, List.of(anchorField));
        if (anchors.isEmpty()) {
            return List.of();
        }
        FieldText anchor = anchors.get(0);
        Map<String, List<Mention>> anchorFamilies = byFamily(extractor.extract(anchor.text()));
        if (anchorFamilies.isEmpty()) {
            return List.of();
        }
        List<Conflict> conflicts = new ArrayList<>();
        for (FieldText other : ListingTexts.of(listing, against)) {
            if (other.field().equals(anchor.field())) {
                continue;
            }
            Map<String, List<Mention>> families = byFamily(extractor.extract(other.text()));
            for (Map.Entry<String, List<Mention>> family : families.entrySet()) {
                List<Mention> mine = anchorFamilies.get(family.getKey());
                if (mine == null) {
                    continue;
                }
                Set<String> anchorKeys = keys(mine);
                if (family.getValue().stream().noneMatch(m -> anchorKeys.contains(m.key()))) {
                    conflicts.add(new Conflict(family.getKey(), anchor, mine.get(0), other, family.getValue().get(0)));
                }
            }
        }
        return conflicts;
    }

    private static Map<String, List<Mention>> byFamily(List<Mention> mentions) {
        Map<String, List<Mention>> families = new LinkedHashMap<>();
        mentions.forEach(m -> families.computeIfAbsent(m.family(), f -> new ArrayList<>()).add(m));
        return families;
    }

    private static Set<String> keys(List<Mention> mentions) {
        Set<String> keys = new LinkedHashSet<>();
        mentions.forEach(m -> keys.add(m.key()));
        return keys;
    }
}

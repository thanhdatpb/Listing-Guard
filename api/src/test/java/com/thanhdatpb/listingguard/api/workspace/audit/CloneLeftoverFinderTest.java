package com.thanhdatpb.listingguard.api.workspace.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.thanhdatpb.listingguard.api.workspace.ListingCloner;
import com.thanhdatpb.listingguard.api.workspace.ListingComparison;
import com.thanhdatpb.listingguard.api.workspace.StandardTargets;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceModels.Listing;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingRole;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingSource;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.ListingStatus;
import com.thanhdatpb.listingguard.api.workspace.WorkspaceTypes.Marketplace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The comparison of a clone with its original, without a database (ADR-039 §4): what the pack {@code clone-leftover} reads in a text of the clone when a
 * variation attribute or the product type is new. At least five cases that must be reported and five that must not, the second kind close to the first
 * (an options sentence, a weight, a unit of another family, a product type of the same kind, the new value written in the text as well).
 */
class CloneLeftoverFinderTest {

    private static final CloneLeftoverFinder FINDER = new CloneLeftoverFinder(new CloneLeftoverPack(), StandardTargets.load());

    /** The standard field a synthetic column was given: the columns that are variation attributes are all given the attribute target. */
    private static final Map<String, String> TARGETS = Map.of("size_name", "attribute", "pack_name", "attribute", "recipient_name", "attribute",
            "color_name", "attribute", "feed_product_type", "product_type", "item_name", "title");

    private record Case(String name, Map<String, String> before, Map<String, String> after, Map<String, String> texts, List<String> reported) {
    }

    private static Map<String, String> data(String... pairs) {
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            data.put(pairs[i], pairs[i + 1]);
        }
        return data;
    }

    private static Listing listing(Map<String, String> data) {
        return new Listing(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SKU", null, ListingRole.STANDALONE, "t", data,
                JsonNodeFactory.instance.objectNode(), "0".repeat(64), null, ListingStatus.DRAFT, ListingSource.CLONE, null, 0, null, null, null);
    }

    private static List<CloneLeftoverFinder.Leftover> find(Case c) {
        List<ListingCloner.Pair> pairs = new ArrayList<>();
        c.before().keySet().forEach(k -> pairs.add(new ListingCloner.Pair(k, k, k, k, TARGETS.get(k))));
        var alignment = new ListingComparison.Alignment(listing(c.before()), null, pairs);
        return FINDER.find(Marketplace.AMAZON, alignment, listing(c.after()), c.texts());
    }

    private static Case c(String name, Map<String, String> before, Map<String, String> after, Map<String, String> texts, String... reported) {
        return new Case(name, before, after, texts, List.of(reported));
    }

    @Test
    void whatTheCloneStillSaysOfItsOriginalIsReportedAndNothingElse() {
        List<Case> cases = List.of(
                c("size in the title", data("size_name", "11oz"), data("size_name", "15oz"), data("title", "Funny Mug 11oz"), "title"),
                c("a size with two dimensions", data("size_name", "12x16 inch"), data("size_name", "16x20 inch"), data("description", "A poster of 12 x 16 inch."),
                        "description"),
                c("a pack", data("pack_name", "Pack of 2"), data("pack_name", "Pack of 4"), data("bullet_points[0]", "A 2 pack of mugs"), "bullet_points[0]"),
                c("a recipient", data("recipient_name", "Mom"), data("recipient_name", "Dad"), data("title", "Funny Gift for Mom"), "title"),
                c("a product type", data("feed_product_type", "MUG"), data("feed_product_type", "SHIRT"), data("title", "Funny Cat Mug"), "title"),
                c("two texts", data("size_name", "11oz"), data("size_name", "15oz"), data("title", "Mug 11oz", "description", "Holds 11oz"),
                        "title", "description"),
                c("a tag", data("size_name", "11oz"), data("size_name", "15oz"), data("tags[0]", "11oz mug"), "tags[0]"),
                c("a size in millilitres", data("size_name", "330 ml"), data("size_name", "500 ml"), data("title", "Bottle 330ml"), "title"),
                // not a leftover
                c("the attribute did not change", data("size_name", "11oz"), data("size_name", "11 OZ"), data("title", "Mug 11oz")),
                c("the new value is in the text too", data("size_name", "11oz"), data("size_name", "15oz"), data("title", "Mug 15oz, was 11oz")),
                c("a colour is no fact", data("color_name", "White"), data("color_name", "Blue"), data("title", "White Mug")),
                c("an options sentence", data("size_name", "11oz"), data("size_name", "15oz"), data("title", "Available in 11oz or 15oz")),
                c("another unit", data("size_name", "11oz"), data("size_name", "15oz"), data("title", "Mug with a 6 inch plate")),
                c("a weight is no size", data("size_name", "11oz"), data("size_name", "15oz"), data("description", "The mug weighs 11 oz.")),
                c("a product type that is not listed", data("feed_product_type", "LUGGAGE"), data("feed_product_type", "SHIRT"), data("title", "Cat Mug")),
                c("a product type of the same kind", data("feed_product_type", "MUG"), data("feed_product_type", "COFFEE_MUG"), data("title", "Cat Mug")),
                c("a text no one reads", data("size_name", "11oz"), data("size_name", "15oz"), data("materials[0]", "ceramic 11oz")),
                c("no old value", data("size_name", ""), data("size_name", "15oz"), data("title", "Mug 11oz")),
                c("the recipient is the same", data("recipient_name", "Mom"), data("recipient_name", "Mom"), data("title", "Gift for Mom")),
                c("both recipients in the text", data("recipient_name", "Mom"), data("recipient_name", "Dad"), data("title", "Gift for Mom and Dad")));

        assertThat(cases.stream().filter(x -> !x.reported().isEmpty()).count()).isGreaterThanOrEqualTo(5);
        assertThat(cases.stream().filter(x -> x.reported().isEmpty()).count()).isGreaterThanOrEqualTo(5);
        for (Case c : cases) {
            assertThat(find(c)).as(c.name()).extracting(CloneLeftoverFinder.Leftover::field).containsExactlyInAnyOrderElementsOf(c.reported());
        }
    }

    @Test
    void aLeftoverNamesBothValuesAndTheSpanIsTheOldValue() {
        Case c = c("size", data("size_name", "11oz"), data("size_name", "15oz"), data("title", "Funny Mug 11oz Gift"), "title");

        CloneLeftoverFinder.Leftover leftover = find(c).get(0);

        assertThat("Funny Mug 11oz Gift".substring(leftover.start(), leftover.end())).isEqualTo("11oz").isEqualTo(leftover.value());
        assertThat(leftover.message()).contains("\"11oz\"").contains("\"15oz\"").contains("size_name").contains("title");
        assertThat(leftover.suggestion()).contains("không tự sửa");
    }

    @Test
    void whenTheTextIsStillExactlyTheOriginalsTheMessageSaysSo() {
        Case same = c("same", data("size_name", "11oz", "item_name", "Mug 11oz"), data("size_name", "15oz", "item_name", "Mug 11oz"), data("title", "Mug 11oz"));
        Case edited = c("edited", data("size_name", "11oz", "item_name", "Mug 11oz"), data("size_name", "15oz", "item_name", "Big Mug 11oz"), data("title", "Big Mug 11oz"));

        assertThat(find(same).get(0).message()).contains("còn giống hệt bản gốc");
        assertThat(find(edited).get(0).message()).doesNotContain("còn giống hệt bản gốc");
    }

    @Test
    void thePackNamesItselfAndItsFileIsHashed() {
        CloneLeftoverPack pack = new CloneLeftoverPack();

        assertThat(pack.version()).isEqualTo("clone-leftover-2026-12");
        assertThat(pack.identity()).startsWith("clone-leftover-2026-12:").hasSize("clone-leftover-2026-12:".length() + 64);
        assertThat(pack.severity()).isEqualTo(com.thanhdatpb.listingguard.core.finding.Severity.WARNING);
        assertThat(pack.fields()).contains("title", "description", "bullet_points", "tags");
    }
}

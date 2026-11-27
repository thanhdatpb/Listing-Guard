package com.thanhdatpb.listingguard.api.demo;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thanhdatpb.listingguard.api.console.bulk.DelimitedText;
import com.thanhdatpb.listingguard.api.console.editor.EditorMarketplace;
import com.thanhdatpb.listingguard.input.StandardListingAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** Bundled synthetic inputs. The fixtures contain no findings, verdicts or dashboard counts. */
final class DemoFixtures {
    record Listing(int line, EditorMarketplace marketplace, String sku, String title, String description, String product) {
        ObjectNode input() {
            ObjectNode listing = JsonNodeFactory.instance.objectNode();
            listing.put("title", title);
            listing.put("description", description);
            listing.put("price", 24.95);
            listing.put("quantity", 25);
            if (marketplace.isEtsy()) {
                listing.put("who_made", "i_did");
                listing.put("when_made", "made_to_order");
                listing.put("category", product);
                listing.putArray("tags").add("gift idea").add("made to order").add("printed gift");
            } else {
                listing.put("brand", "Generic");
                listing.put("product_type", product);
                listing.putArray("bullet_points").add("Printed to order").add("Designed for everyday use");
            }
            return StandardListingAdapter.toStandardInput(marketplace.code(), listing);
        }

        Listing withTitle(String newTitle) {
            return new Listing(line, marketplace, sku, newTitle, description, product);
        }
    }

    private DemoFixtures() {}

    static List<Listing> listings(String file) {
        return rows(file).stream().map(row -> {
            List<String> cells = row.cells();
            return new Listing(row.line(), EditorMarketplace.parse(cells.get(0)).orElseThrow(),
                    cells.get(1), cells.get(2), cells.get(3), cells.get(4));
        }).toList();
    }

    static List<DelimitedText.Row> rows(String file) {
        try {
            var resource = new ClassPathResource("demo/" + file);
            return DelimitedText.parse(resource.getContentAsString(StandardCharsets.UTF_8), ',').rows().stream()
                    .skip(1).filter(row -> !row.blank()).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read bundled demo fixture " + file, e);
        }
    }
}

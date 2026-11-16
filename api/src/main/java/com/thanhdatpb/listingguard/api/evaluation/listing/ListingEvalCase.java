package com.thanhdatpb.listingguard.api.evaluation.listing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One labelled sample listing of {@code eval/listing/cases/} (ADR-032): the listing in the standard input of
 * {@code docs/input-schema.md} and <b>every</b> finding a correct checker reports on it. A finding the checker reports that the
 * case does not expect is a false positive, an expected one it does not report a false negative.
 *
 * <pre>
 * id: etsy-03-title-too-long       # equals the file name without .yaml
 * marketplace: ETSY                # ETSY or AMAZON
 * note: What the case is about.    # required, one or two sentences
 * listing: {...}                   # the standard input
 * expected:                        # every expected finding; [] for a clean listing
 *   - rule: title_length           # a rule of the active ruleset of the marketplace
 *     field: title                 # optional: the finding's field must be this one
 *   - rule: trademark/catalog_exact  # a trademark rule, scanned with the DEMO catalog
 *     mark: Zibblefrax             # optional: the catalog mark the finding names
 * trademark_risk: HIGH             # optional: the overall trademark level the case expects
 * </pre>
 *
 * The loader is strict: an unknown key, a missing one or an {@code id} that differs from the file name fails the whole set,
 * so a typo cannot quietly turn a case into a different one.
 */
public record ListingEvalCase(String id, String marketplace, String note, JsonNode listing, List<Expected> expected,
                              RiskLevel trademarkRisk) {

    /** One expected finding. {@code field} and {@code mark} are null when the case does not pin them. */
    public record Expected(String rule, String field, String mark) {
    }

    public static final String TRADEMARK_PREFIX = "trademark/";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> KEYS = Set.of("id", "marketplace", "note", "listing", "expected", "trademark_risk");
    private static final Set<String> EXPECTED_KEYS = Set.of("rule", "field", "mark");

    /** Every case of a directory ({@code *.yaml}), sorted by file name. */
    public static List<ListingEvalCase> loadAll(Path dir) {
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(file -> file.getFileName().toString().endsWith(".yaml")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the sample cases in " + dir + ": " + e.getMessage(), e);
        }
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No sample case (*.yaml) in " + dir + ".");
        }
        List<ListingEvalCase> cases = new ArrayList<>();
        for (Path file : files) {
            cases.add(load(file));
        }
        return List.copyOf(cases);
    }

    /** @throws IllegalArgumentException naming the file when the case is not valid */
    public static ListingEvalCase load(Path file) {
        String name = file.getFileName().toString();
        String stem = name.substring(0, name.length() - ".yaml".length());
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), stem);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the sample case " + file + ": " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Sample case " + file + ": " + e.getMessage(), e);
        }
    }

    /** @param expectedId the id the case must carry (its file name without .yaml), or null to accept any */
    public static ListingEvalCase parse(String yaml, String expectedId) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (IOException e) {
            throw new IllegalArgumentException("not valid YAML: " + firstLine(e.getMessage()), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("a case is a YAML mapping with " + KEYS + ".");
        }
        Iterator<String> names = root.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("'" + key + "' is not a key of a case; the keys are " + KEYS + ".");
            }
        }
        String id = text(root, "id");
        if (expectedId != null && !id.equals(expectedId)) {
            throw new IllegalArgumentException("id '" + id + "' must equal the file name without .yaml ('" + expectedId + "').");
        }
        String marketplace = text(root, "marketplace").toUpperCase(Locale.ROOT);
        if (!marketplace.equals("ETSY") && !marketplace.equals("AMAZON")) {
            throw new IllegalArgumentException("marketplace must be ETSY or AMAZON, not '" + marketplace + "'.");
        }
        String note = text(root, "note");
        JsonNode listing = root.get("listing");
        if (listing == null || !listing.isObject()) {
            throw new IllegalArgumentException("'listing' must be a mapping in the standard input (docs/input-schema.md).");
        }
        JsonNode expectedNode = root.get("expected");
        if (expectedNode == null || !expectedNode.isArray()) {
            throw new IllegalArgumentException("'expected' must be a list (write [] for a listing with no finding).");
        }
        List<Expected> expected = new ArrayList<>();
        for (JsonNode item : expectedNode) {
            expected.add(expected(item));
        }
        RiskLevel risk = null;
        JsonNode riskNode = root.get("trademark_risk");
        if (riskNode != null) {
            try {
                risk = RiskLevel.valueOf(riskNode.asText().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("trademark_risk must be LOW, MEDIUM, HIGH or UNKNOWN, not '"
                        + riskNode.asText() + "'.");
            }
        }
        return new ListingEvalCase(id, marketplace, note, listing, List.copyOf(expected), risk);
    }

    private static Expected expected(JsonNode item) {
        if (!item.isObject()) {
            throw new IllegalArgumentException("each entry of 'expected' is a mapping with 'rule' and optional 'field', 'mark'.");
        }
        Iterator<String> names = item.fieldNames();
        while (names.hasNext()) {
            String key = names.next();
            if (!EXPECTED_KEYS.contains(key)) {
                throw new IllegalArgumentException("'" + key + "' is not a key of an expected finding; the keys are "
                        + EXPECTED_KEYS + ".");
            }
        }
        String rule = text(item, "rule");
        String field = optionalText(item, "field");
        String mark = optionalText(item, "mark");
        if (mark != null && !rule.startsWith(TRADEMARK_PREFIX)) {
            throw new IllegalArgumentException("'mark' is only for a trademark rule (" + TRADEMARK_PREFIX + "...), not " + rule + ".");
        }
        return new Expected(rule, field, mark);
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int end = message.indexOf('\n');
        return end < 0 ? message : message.substring(0, end);
    }

    private static String text(JsonNode node, String key) {
        String value = optionalText(node, key);
        if (value == null) {
            throw new IllegalArgumentException("'" + key + "' is required.");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isValueNode() || value.asText().isBlank()) {
            throw new IllegalArgumentException("'" + key + "' must be a non-empty text.");
        }
        return value.asText().trim();
    }
}

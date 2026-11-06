package com.thanhdatpb.listingguard.variation.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.variation.Texts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a variation ruleset YAML into a {@link VariationRuleset}. Strict on purpose, like the other two loaders: an
 * unknown or missing key, a duplicate key, an unknown rule or finding kind, a message with an unknown placeholder, a
 * theme that is used and not defined, a {@code verified} or {@code secondary} source without a URL, and an {@code ERROR}
 * that is not backed by a {@code verified} source all fail here, at load time (ADR-001, ADR-006, ADR-015).
 *
 * <p>The content hash is computed over the text after line endings are normalized to LF and a leading BOM is dropped,
 * so the same file hashes the same on Windows and on Linux CI (ADR-002).
 */
public final class VariationRulesetLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[a-z0-9_]{1,64}");

    private VariationRulesetLoader() {
    }

    public static VariationRuleset loadClasspath(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = VariationRulesetLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new VariationRulesetException("Ruleset not found on classpath: " + path);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new VariationRulesetException("Cannot read ruleset " + path, e);
        }
    }

    public static VariationRuleset parse(String yamlText) {
        String text = normalize(yamlText);
        JsonNode tree;
        try {
            tree = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new VariationRulesetException("Invalid ruleset YAML: " + e.getOriginalMessage(), e);
        }
        Section root = Section.root(tree);

        String packId = root.text("pack_id");
        if (!VariationRuleset.PACK_ID.equals(packId)) {
            throw new VariationRulesetException("pack_id must be '" + VariationRuleset.PACK_ID + "', got '" + packId + "'");
        }
        String version = root.text("version");
        if (!version.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new VariationRulesetException("version must match [a-z0-9][a-z0-9._-]{0,63}, got '" + version + "'");
        }
        LocalDate checkedAt = parseDate(root.text("checked_at"));
        String disclaimer = root.text("disclaimer");

        Section evidence = root.section("evidence");
        int maxValueChars = evidence.integer("max_value_chars", 20, 500);
        evidence.finish();

        Map<String, VariationRuleset.Theme> themes = parseThemes(root.section("themes"));

        Section rules = root.section("rules");
        List<String> order = ruleOrder(rules);
        Map<String, VariationRuleset.RuleSpec> specs = new HashMap<>();

        // each rule: its common keys, then its own, then a check that nothing else is in its section
        Section section = rules.section(RuleIds.PARENT_CHILD_STRUCTURE);
        specs.put(RuleIds.PARENT_CHILD_STRUCTURE, parseCommon(RuleIds.PARENT_CHILD_STRUCTURE, section));
        section.finish();

        section = rules.section(RuleIds.VARIATION_THEME_ALLOWED);
        specs.put(RuleIds.VARIATION_THEME_ALLOWED, parseCommon(RuleIds.VARIATION_THEME_ALLOWED, section));
        VariationRuleset.ThemeAllowed themeAllowed = parseThemeAllowed(section, themes);
        section.finish();

        section = rules.section(RuleIds.APPAREL_SIZE_ATTRIBUTES);
        specs.put(RuleIds.APPAREL_SIZE_ATTRIBUTES, parseCommon(RuleIds.APPAREL_SIZE_ATTRIBUTES, section));
        VariationRuleset.Apparel apparel = parseApparel(section);
        section.finish();

        section = rules.section(RuleIds.DUPLICATE_COMBINATION);
        specs.put(RuleIds.DUPLICATE_COMBINATION, parseCommon(RuleIds.DUPLICATE_COMBINATION, section));
        section.finish();

        section = rules.section(RuleIds.DUPLICATE_SKU);
        specs.put(RuleIds.DUPLICATE_SKU, parseCommon(RuleIds.DUPLICATE_SKU, section));
        section.finish();

        section = rules.section(RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME);
        specs.put(RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME, parseCommon(RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME, section));
        VariationRuleset.ChildrenDiffer differ = parseChildrenDiffer(section);
        section.finish();

        section = rules.section(RuleIds.ETSY_VARIATION_LIMITS);
        specs.put(RuleIds.ETSY_VARIATION_LIMITS, parseCommon(RuleIds.ETSY_VARIATION_LIMITS, section));
        VariationRuleset.EtsyLimits etsy = parseEtsyLimits(section);
        section.finish();

        rules.finish();
        root.finish();

        List<VariationRuleset.RuleSpec> ordered = order.stream().map(specs::get).toList();
        return new VariationRuleset(packId, version, checkedAt, sha256(text), disclaimer, maxValueChars, themes, ordered,
                themeAllowed, apparel, differ, etsy);
    }

    /** LF line endings, no BOM. This is the text that gets hashed. */
    static String normalize(String text) {
        String withoutBom = text.startsWith("﻿") ? text.substring(1) : text;
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ---- rules and their common keys -------------------------------------------------------------------------------

    /** The declared order of the rules; every known rule exactly once. */
    private static List<String> ruleOrder(Section rules) {
        List<String> declared = rules.keys();
        for (String id : declared) {
            if (!RuleIds.ALL.contains(id)) {
                throw new VariationRulesetException("rules declares unknown rule '" + id + "'");
            }
        }
        for (String id : RuleIds.ALL) {
            if (!declared.contains(id)) {
                throw new VariationRulesetException("rules is missing required rule '" + id + "'");
            }
        }
        return declared;
    }

    /**
     * Reads the keys every rule has ({@code marketplaces}, {@code source}, {@code kinds}) from the rule's section. The
     * caller then reads the rule's own keys from the same section and calls {@code finish()} on it, which fails on any
     * key nobody read.
     */
    private static VariationRuleset.RuleSpec parseCommon(String id, Section section) {
        Source source = parseSource(section.section("source"));
        Set<String> marketplaces = parseMarketplaces(id, section);
        Map<String, VariationRuleset.Kind> kinds = parseKinds(id, section.section("kinds"), source);
        return new VariationRuleset.RuleSpec(id, marketplaces, source, kinds);
    }

    private static Set<String> parseMarketplaces(String id, Section section) {
        Set<String> result = new LinkedHashSet<>();
        for (String entry : section.strings("marketplaces", true)) {
            String code = entry.toUpperCase(Locale.ROOT);
            if (!RuleIds.SUPPORTED_MARKETPLACES.get(id).contains(code)) {
                throw new VariationRulesetException(section.path() + ".marketplaces lists '" + entry + "' but rule " + id
                        + " can only run for " + RuleIds.SUPPORTED_MARKETPLACES.get(id).stream().sorted().toList());
            }
            if (!result.add(code)) {
                throw new VariationRulesetException(section.path() + ".marketplaces lists '" + entry + "' twice");
            }
        }
        return result;
    }

    private static Map<String, VariationRuleset.Kind> parseKinds(String ruleId, Section kinds, Source ruleSource) {
        Map<String, Set<String>> expected = RuleIds.KINDS.get(ruleId);
        Map<String, Section> declared = kinds.children();
        for (String name : declared.keySet()) {
            if (!expected.containsKey(name)) {
                throw new VariationRulesetException(kinds.path() + " declares unknown kind '" + name + "'");
            }
        }
        for (String name : expected.keySet()) {
            if (!declared.containsKey(name)) {
                throw new VariationRulesetException(kinds.path() + " is missing required kind '" + name + "'");
            }
        }
        Map<String, VariationRuleset.Kind> result = new LinkedHashMap<>();
        for (Map.Entry<String, Section> entry : declared.entrySet()) {
            Section kind = entry.getValue();
            Severity severity = parseSeverity(kind, ruleId, ruleSource);
            String message = template(kind, "message", expected.get(entry.getKey()));
            String suggestion = template(kind, "suggestion", expected.get(entry.getKey()));
            kind.finish();
            result.put(entry.getKey(), new VariationRuleset.Kind(entry.getKey(), severity, message, suggestion));
        }
        kinds.finish();
        return result;
    }

    private static Severity parseSeverity(Section kind, String ruleId, Source ruleSource) {
        String value = kind.text("severity");
        Severity severity;
        try {
            severity = Severity.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new VariationRulesetException(kind.path() + ".severity must be ERROR, WARNING or INFO, got '" + value + "'");
        }
        if (severity == Severity.ERROR) {
            if (RuleIds.CHILDREN_DIFFER_OUTSIDE_THEME.equals(ruleId)) {
                throw new VariationRulesetException(kind.path() + ".severity must not be ERROR: the rule is a heuristic");
            }
            if (ruleSource.status() != SourceStatus.VERIFIED) {
                throw new VariationRulesetException(kind.path() + ".severity is ERROR but the source of rule " + ruleId
                        + " is '" + ruleSource.status().yaml() + "': ERROR needs a verified source (ADR-006)");
            }
        }
        return severity;
    }

    private static String template(Section section, String key, Set<String> allowed) {
        String text = section.text(key);
        Templates.validate(section.path() + "." + key, text, allowed);
        return text;
    }

    static Source parseSource(Section section) {
        SourceStatus status = SourceStatus.parse(section.path() + ".status", section.text("status"));
        List<String> urls = section.optionalStrings("urls");
        for (String url : urls) {
            if (!url.startsWith("https://")) {
                throw new VariationRulesetException(section.path() + ".urls must be https URLs, got '" + url + "'");
            }
        }
        String note = section.optionalText("note");
        section.finish();
        if (status != SourceStatus.UNVERIFIED && urls.isEmpty()) {
            throw new VariationRulesetException(section.path() + " is '" + status.yaml() + "' but lists no URL: a value is "
                    + "not marked above 'unverified' without a page to point at");
        }
        return new Source(status, urls, note == null || note.isBlank() ? null : note.trim());
    }

    // ---- shared tables ---------------------------------------------------------------------------------------------

    private static Map<String, VariationRuleset.Theme> parseThemes(Section themes) {
        Map<String, VariationRuleset.Theme> result = new LinkedHashMap<>();
        for (Map.Entry<String, Section> entry : themes.children().entrySet()) {
            Section theme = entry.getValue();
            List<String> attributes = attributeNames(theme, "attributes");
            Source source = parseSource(theme.section("source"));
            theme.finish();
            if (result.put(Texts.fold(entry.getKey()), new VariationRuleset.Theme(entry.getKey().trim(), attributes, source)) != null) {
                throw new VariationRulesetException("themes lists '" + entry.getKey() + "' twice (ignoring case)");
            }
        }
        themes.finish();
        return result;
    }

    private static List<String> attributeNames(Section section, String key) {
        List<String> names = section.strings(key, true);
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!ATTRIBUTE_NAME.matcher(name).matches()) {
                throw new VariationRulesetException(section.path() + "." + key + " entry '" + name
                        + "' must be lower case letters, digits and '_'");
            }
            if (!seen.add(name)) {
                throw new VariationRulesetException(section.path() + "." + key + " lists '" + name + "' twice");
            }
        }
        return names;
    }

    // ---- rules with settings ---------------------------------------------------------------------------------------

    private static VariationRuleset.ThemeAllowed parseThemeAllowed(Section rule, Map<String, VariationRuleset.Theme> themes) {
        Map<String, VariationRuleset.ProductTypeThemes> productTypes = new LinkedHashMap<>();
        for (Map.Entry<String, Section> entry : rule.sections("product_types").entrySet()) {
            Section productType = entry.getValue();
            List<String> names = productType.strings("themes", true);
            Set<String> seen = new HashSet<>();
            for (String name : names) {
                if (!themes.containsKey(Texts.fold(name))) {
                    throw new VariationRulesetException(productType.path() + ".themes lists '" + name
                            + "' which is not defined in themes");
                }
                if (!seen.add(Texts.fold(name))) {
                    throw new VariationRulesetException(productType.path() + ".themes lists '" + name + "' twice");
                }
            }
            Source source = parseSource(productType.section("source"));
            productType.finish();
            if (productTypes.put(Texts.fold(entry.getKey()),
                    new VariationRuleset.ProductTypeThemes(entry.getKey().trim(), names, source)) != null) {
                throw new VariationRulesetException(rule.path() + ".product_types lists '" + entry.getKey()
                        + "' twice (ignoring case)");
            }
        }
        return new VariationRuleset.ThemeAllowed(productTypes);
    }

    private static VariationRuleset.Apparel parseApparel(Section rule) {
        Section productTypes = rule.section("product_types");
        Set<String> covered = new LinkedHashSet<>();
        for (String name : productTypes.strings("value", true)) {
            if (!covered.add(Texts.fold(name))) {
                throw new VariationRulesetException(productTypes.path() + ".value lists '" + name + "' twice (ignoring case)");
            }
        }
        Source productTypesSource = parseSource(productTypes.section("source"));
        productTypes.finish();

        List<String> required = attributeNames(rule, "required_attributes");
        Map<String, List<String>> valueLists = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : rule.stringListMap("value_lists").entrySet()) {
            if (!required.contains(entry.getKey())) {
                throw new VariationRulesetException(rule.path() + ".value_lists has '" + entry.getKey()
                        + "' which is not in required_attributes");
            }
            Set<String> seen = new HashSet<>();
            for (String value : entry.getValue()) {
                if (!seen.add(Texts.fold(value))) {
                    throw new VariationRulesetException(rule.path() + ".value_lists." + entry.getKey() + " lists '"
                            + value + "' twice (ignoring case)");
                }
            }
            valueLists.put(entry.getKey(), entry.getValue());
        }

        Section range = rule.section("range");
        String classAttribute = range.text("class_attribute");
        String sizeAttribute = range.text("size_attribute");
        String sizeToAttribute = range.text("size_to_attribute");
        for (String name : List.of(classAttribute, sizeAttribute, sizeToAttribute)) {
            if (!ATTRIBUTE_NAME.matcher(name).matches()) {
                throw new VariationRulesetException(range.path() + " attribute '" + name
                        + "' must be lower case letters, digits and '_'");
            }
        }
        if (!required.contains(classAttribute) || !valueLists.containsKey(classAttribute)) {
            throw new VariationRulesetException(range.path() + ".class_attribute '" + classAttribute
                    + "' must be a required attribute with a value list");
        }
        if (!required.contains(sizeAttribute)) {
            throw new VariationRulesetException(range.path() + ".size_attribute '" + sizeAttribute
                    + "' must be a required attribute");
        }
        Set<String> classValues = new HashSet<>();
        valueLists.get(classAttribute).forEach(value -> classValues.add(Texts.fold(value)));
        Map<String, VariationRuleset.RangeKind> classes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : range.stringMap("classes").entrySet()) {
            String folded = Texts.fold(entry.getKey());
            if (!classValues.contains(folded)) {
                throw new VariationRulesetException(range.path() + ".classes has '" + entry.getKey()
                        + "' which is not a value of " + classAttribute);
            }
            VariationRuleset.RangeKind kind;
            try {
                kind = VariationRuleset.RangeKind.valueOf(entry.getValue().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new VariationRulesetException(range.path() + ".classes." + entry.getKey()
                        + " must be numeric, alpha or none, got '" + entry.getValue() + "'");
            }
            classes.put(folded, kind);
        }
        range.finish();

        Section scale = rule.section("alpha_scale");
        Source scaleSource = parseSource(scale.section("source"));
        List<List<String>> order = new ArrayList<>();
        Set<String> seenLabels = new HashSet<>();
        for (List<String> rank : scale.stringLists("order")) {
            List<String> folded = new ArrayList<>();
            for (String label : rank) {
                if (!seenLabels.add(Texts.fold(label))) {
                    throw new VariationRulesetException(scale.path() + ".order lists '" + label + "' twice (ignoring case)");
                }
                folded.add(Texts.fold(label));
            }
            order.add(folded);
        }
        scale.finish();

        return new VariationRuleset.Apparel(covered, productTypesSource, required, valueLists,
                new VariationRuleset.Range(classAttribute, sizeAttribute, sizeToAttribute, classes),
                new VariationRuleset.AlphaScale(order, scaleSource));
    }

    private static VariationRuleset.ChildrenDiffer parseChildrenDiffer(Section rule) {
        Section ignore = rule.section("ignore_words");
        Set<String> words = new LinkedHashSet<>();
        for (String key : List.of("size_words", "color_words")) {
            for (String entry : ignore.strings(key, true)) {
                List<String> tokens = Texts.tokens(entry);
                if (tokens.isEmpty()) {
                    throw new VariationRulesetException(ignore.path() + "." + key + " entry '" + entry
                            + "' has no letters or digits");
                }
                words.addAll(tokens);
            }
        }
        Source source = parseSource(ignore.section("source"));
        ignore.finish();
        return new VariationRuleset.ChildrenDiffer(words, source);
    }

    private static VariationRuleset.EtsyLimits parseEtsyLimits(Section rule) {
        Section maxTypes = rule.section("max_variation_types");
        int max = maxTypes.integer("value", 1, 100);
        Source maxSource = parseSource(maxTypes.section("source"));
        maxTypes.finish();

        Section unsupported = rule.section("unsupported_properties");
        List<String> properties = unsupported.strings("value", true);
        Set<String> seen = new HashSet<>();
        for (String property : properties) {
            if (!seen.add(Texts.fold(property))) {
                throw new VariationRulesetException(unsupported.path() + ".value lists '" + property + "' twice (ignoring case)");
            }
        }
        Source unsupportedSource = parseSource(unsupported.section("source"));
        unsupported.finish();

        Section photos = rule.section("photo_variation_limit");
        int photoLimit = photos.integer("value", 1, 1000);
        Source photoSource = parseSource(photos.section("source"));
        photos.finish();
        return new VariationRuleset.EtsyLimits(max, maxSource, properties, unsupportedSource, photoLimit, photoSource);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new VariationRulesetException("checked_at must be an ISO date (yyyy-MM-dd), got '" + value + "'");
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

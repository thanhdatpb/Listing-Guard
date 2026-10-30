package com.thanhdatpb.listingguard.trademark.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.trademark.AlgorithmVersions;
import com.thanhdatpb.listingguard.trademark.RiskLevel;
import com.thanhdatpb.listingguard.trademark.normalize.NormalizationSettings;
import com.thanhdatpb.listingguard.trademark.normalize.TextNormalizer;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses a trademark ruleset YAML into a {@link TrademarkRuleset}. Strict on purpose, like the rule-engine loader:
 * an unknown or missing key, a duplicate key, a bad risk level, a message with an unknown placeholder, a rule
 * declared out of order and any {@code source.status} other than {@code unverified} fail here, at load time.
 *
 * <p>The content hash is computed over the text after line endings are normalized to LF and a leading BOM is
 * dropped, so the same file hashes the same on Windows and on Linux CI (ADR-002).
 */
public final class TrademarkRulesetLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private static final Set<String> CATALOG_PLACEHOLDERS = Set.of("mark", "token", "field");
    private static final Set<String> FUZZY_PLACEHOLDERS = Set.of("mark", "token", "field", "score");
    private static final Set<String> GEO_PLACEHOLDERS = Set.of("place", "sport", "field", "distance", "max_distance");
    private static final Set<String> PURE_COLOR_PLACEHOLDERS = Set.of("phrase", "field", "tokens");

    private TrademarkRulesetLoader() {
    }

    public static TrademarkRuleset loadClasspath(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = TrademarkRulesetLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new TrademarkRulesetException("Ruleset not found on classpath: " + path);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TrademarkRulesetException("Cannot read ruleset " + path, e);
        }
    }

    public static TrademarkRuleset parse(String yamlText) {
        String text = normalize(yamlText);
        JsonNode tree;
        try {
            tree = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new TrademarkRulesetException("Invalid ruleset YAML: " + e.getOriginalMessage(), e);
        }
        Section root = Section.root(tree);

        String packId = root.text("pack_id");
        if (!TrademarkRuleset.PACK_ID.equals(packId)) {
            throw new TrademarkRulesetException("pack_id must be '" + TrademarkRuleset.PACK_ID + "', got '" + packId + "'");
        }
        String version = root.text("version");
        if (!version.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new TrademarkRulesetException("version must match [a-z0-9][a-z0-9._-]{0,63}, got '" + version + "'");
        }
        LocalDate checkedAt = parseDate(root.text("checked_at"));
        String disclaimer = root.text("disclaimer");

        NormalizationSettings normalization = parseNormalization(root.section("normalization"));
        TextNormalizer normalizer = new TextNormalizer(normalization);
        TrademarkRuleset.Matching matching = parseMatching(root.section("matching"));
        TrademarkRuleset.Lexicon lexicon = parseLexicon(root.section("lexicon"), normalizer);

        Section rules = root.section("rules");
        List<String> order = ruleOrder(rules);
        TrademarkRuleset.CatalogRule exact = parseCatalogRule(rules.section(RuleIds.CATALOG_EXACT), CATALOG_PLACEHOLDERS);
        TrademarkRuleset.CatalogRule phrase = parseCatalogRule(rules.section(RuleIds.CATALOG_PHRASE), CATALOG_PLACEHOLDERS);
        TrademarkRuleset.CatalogRule fuzzy = parseCatalogRule(rules.section(RuleIds.CATALOG_FUZZY), FUZZY_PLACEHOLDERS);
        TrademarkRuleset.GeoSport geo = parseGeoSport(rules.section(RuleIds.GEO_SPORT_COMBINATION));
        TrademarkRuleset.ColorContainsMark colorContains =
                parseColorContainsMark(rules.section(RuleIds.COLOR_NAME_CONTAINS_MARK));
        TrademarkRuleset.PureColor pureColor = parsePureColor(rules.section(RuleIds.PURE_COLOR_DESCRIPTION));
        rules.finish();
        root.finish();

        return new TrademarkRuleset(packId, version, checkedAt, sha256(text), disclaimer, normalization, matching,
                lexicon, order, exact, phrase, fuzzy, geo, colorContains, pureColor);
    }

    /** LF line endings, no BOM. This is the text that gets hashed. */
    static String normalize(String text) {
        String withoutBom = text.startsWith("﻿") ? text.substring(1) : text;
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static NormalizationSettings parseNormalization(Section section) {
        parseSource(section.section("source"));
        Map<Integer, String> fold = codePointMap(section, "fold_map");
        Map<Integer, String> homoglyphs = codePointMap(section, "homoglyphs");
        Section leet = section.section("leet");
        int minLetters = leet.integer("min_letters_around", 1, 5);
        Map<Integer, String> leetMap = codePointMap(leet, "map");
        leet.finish();
        Section punctuation = section.section("punctuation");
        Set<Integer> delete = new LinkedHashSet<>();
        for (String entry : punctuation.strings("delete_chars", false)) {
            delete.add(singleCodePoint(punctuation.path() + ".delete_chars", entry));
        }
        punctuation.finish();
        section.finish();
        return new NormalizationSettings(fold, homoglyphs, leetMap, minLetters, delete);
    }

    /** A mapping whose keys are single characters, as code points. */
    private static Map<Integer, String> codePointMap(Section parent, String key) {
        Map<Integer, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : parent.stringMap(key).entrySet()) {
            result.put(singleCodePoint(parent.path() + "." + key + " keys", entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static int singleCodePoint(String where, String value) {
        if (value.codePointCount(0, value.length()) != 1) {
            throw new TrademarkRulesetException(where + " entries must be a single character, got '" + value + "'");
        }
        return value.codePointAt(0);
    }

    private static TrademarkRuleset.Matching parseMatching(Section section) {
        parseSource(section.section("source"));
        List<String> fields = section.strings("scan_fields", true);
        if (new HashSet<>(fields).size() != fields.size()) {
            throw new TrademarkRulesetException("matching.scan_fields contains a duplicate");
        }
        for (String field : fields) {
            if (!TrademarkRuleset.SCANNABLE_FIELDS.contains(field)) {
                throw new TrademarkRulesetException("matching.scan_fields has unknown field '" + field + "' (use "
                        + TrademarkRuleset.SCANNABLE_FIELDS.stream().sorted().toList() + ")");
            }
        }
        int ngram = section.integer("max_ngram_tokens", 1, 8);
        int minChars = section.integer("fuzzy_min_chars", 1, 200);
        int maxChars = section.integer("fuzzy_max_chars", 1, 200);
        if (minChars > maxChars) {
            throw new TrademarkRulesetException("matching.fuzzy_min_chars must not exceed matching.fuzzy_max_chars");
        }
        double candidate = section.fraction("candidate_threshold");
        double finalThreshold = section.fraction("final_threshold");
        String scoring = section.text("scoring_algorithm_version");
        if (!AlgorithmVersions.SCORING.equals(scoring)) {
            throw new TrademarkRulesetException("matching.scoring_algorithm_version is '" + scoring
                    + "' but this build implements '" + AlgorithmVersions.SCORING + "'");
        }
        int snippet = section.integer("max_snippet_chars", 1, 500);
        section.finish();
        return new TrademarkRuleset.Matching(fields, ngram, minChars, maxChars, candidate, finalThreshold, scoring,
                snippet);
    }

    private static TrademarkRuleset.Lexicon parseLexicon(Section section, TextNormalizer normalizer) {
        parseSource(section.section("source"));
        List<List<String>> places = phraseList(section, "places", normalizer);
        List<List<String>> sports = phraseList(section, "sport_terms", normalizer);
        Set<String> colors = wordSet(section, "color_words", normalizer);
        Set<String> patterns = wordSet(section, "pattern_words", normalizer);
        section.finish();
        return new TrademarkRuleset.Lexicon(places, sports, colors, patterns);
    }

    private static List<List<String>> phraseList(Section section, String key, TextNormalizer normalizer) {
        List<List<String>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entry : section.strings(key, true)) {
            List<String> tokens = normalizer.tokenTexts(entry);
            if (tokens.isEmpty()) {
                throw new TrademarkRulesetException("lexicon." + key + " entry '" + entry + "' has no letters or digits");
            }
            if (!seen.add(String.join(" ", tokens))) {
                throw new TrademarkRulesetException("lexicon." + key + " lists '" + entry + "' twice (after normalization)");
            }
            result.add(tokens);
        }
        return result;
    }

    private static Set<String> wordSet(Section section, String key, TextNormalizer normalizer) {
        Set<String> result = new LinkedHashSet<>();
        for (String entry : section.strings(key, true)) {
            List<String> tokens = normalizer.tokenTexts(entry);
            if (tokens.size() != 1) {
                throw new TrademarkRulesetException("lexicon." + key + " entry '" + entry + "' must be a single word");
            }
            if (!result.add(tokens.get(0))) {
                throw new TrademarkRulesetException("lexicon." + key + " lists '" + entry + "' twice (after normalization)");
            }
        }
        return result;
    }

    /** The declared order of the rules; every known rule exactly once, and each after the rules it needs. */
    private static List<String> ruleOrder(Section rules) {
        List<String> declared = rules.keys();
        for (String id : declared) {
            if (!RuleIds.ALL.contains(id)) {
                throw new TrademarkRulesetException("rules declares unknown rule '" + id + "'");
            }
        }
        for (String id : RuleIds.ALL) {
            if (!declared.contains(id)) {
                throw new TrademarkRulesetException("rules is missing required rule '" + id + "'");
            }
        }
        RuleIds.REQUIRES.forEach((rule, needed) -> needed.forEach(prerequisite -> {
            if (declared.indexOf(prerequisite) > declared.indexOf(rule)) {
                throw new TrademarkRulesetException("rules must declare '" + prerequisite + "' before '" + rule
                        + "', which reads its results");
            }
        }));
        return declared;
    }

    private static TrademarkRuleset.CatalogRule parseCatalogRule(Section section, Set<String> placeholders) {
        parseSource(section.section("source"));
        RiskLevel risk = parseRisk(section);
        String message = template(section, "message", placeholders);
        String suggestion = template(section, "suggestion", placeholders);
        section.finish();
        return new TrademarkRuleset.CatalogRule(risk, message, suggestion);
    }

    private static TrademarkRuleset.GeoSport parseGeoSport(Section section) {
        parseSource(section.section("source"));
        RiskLevel risk = parseRisk(section);
        int distance = section.integer("max_token_distance", 1, 50);
        String message = template(section, "message", GEO_PLACEHOLDERS);
        String suggestion = template(section, "suggestion", GEO_PLACEHOLDERS);
        section.finish();
        return new TrademarkRuleset.GeoSport(risk, distance, message, suggestion);
    }

    private static TrademarkRuleset.ColorContainsMark parseColorContainsMark(Section section) {
        parseSource(section.section("source"));
        String note = template(section, "note", Set.of());
        section.finish();
        return new TrademarkRuleset.ColorContainsMark(note);
    }

    private static TrademarkRuleset.PureColor parsePureColor(Section section) {
        parseSource(section.section("source"));
        RiskLevel risk = parseRisk(section);
        if (risk != RiskLevel.LOW) {
            throw new TrademarkRulesetException(section.path() + ".risk must be LOW: the finding is informational and "
                    + "must not raise the overall risk (ADR-009)");
        }
        int minTokens = section.integer("min_tokens", 1, 20);
        String message = template(section, "message", PURE_COLOR_PLACEHOLDERS);
        String suggestion = template(section, "suggestion", PURE_COLOR_PLACEHOLDERS);
        section.finish();
        return new TrademarkRuleset.PureColor(risk, minTokens, message, suggestion);
    }

    private static String template(Section section, String key, Set<String> allowed) {
        String text = section.text(key);
        Templates.validate(section.path() + "." + key, text, allowed);
        return text;
    }

    private static RiskLevel parseRisk(Section section) {
        String value = section.text("risk");
        try {
            RiskLevel risk = RiskLevel.valueOf(value.toUpperCase(Locale.ROOT));
            if (risk.isDetermined()) {
                return risk;
            }
        } catch (IllegalArgumentException ignored) {
            // reported below
        }
        throw new TrademarkRulesetException(section.path() + ".risk must be LOW, MEDIUM or HIGH, got '" + value + "'");
    }

    /** Every block's source is an internal heuristic: only {@code unverified} is accepted (ADR-009). */
    private static void parseSource(Section source) {
        String status = source.text("status");
        if (!"unverified".equalsIgnoreCase(status)) {
            throw new TrademarkRulesetException(source.path() + ".status must be 'unverified' (these are internal "
                    + "heuristics, not a marketplace or legal rule), got '" + status + "'");
        }
        source.optionalText("note");
        source.finish();
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new TrademarkRulesetException("checked_at must be an ISO date (yyyy-MM-dd), got '" + value + "'");
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

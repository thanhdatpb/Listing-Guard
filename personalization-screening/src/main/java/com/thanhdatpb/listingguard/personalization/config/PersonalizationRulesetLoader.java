package com.thanhdatpb.listingguard.personalization.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.personalization.Texts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a personalization ruleset YAML into a {@link PersonalizationRuleset}. Strict on purpose, like the other loaders:
 * an unknown or missing key, a duplicate key, an unknown rule or kind, a message with an unknown placeholder, a class or
 * profile that is referred to and not declared, a source status other than {@code unverified}, and an {@code ERROR} all
 * fail here, at load time (ADR-001, ADR-006, ADR-016).
 *
 * <p>The content hash is computed over the text after line endings are normalized to LF and a leading BOM is dropped, so
 * the same file hashes the same on Windows and on Linux CI (ADR-002).
 */
public final class PersonalizationRulesetLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private static final Pattern PROFILE_CODE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern CLASS_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern RANGE = Pattern.compile("U\\+([0-9A-Fa-f]{4,6})-U\\+([0-9A-Fa-f]{4,6})");

    private PersonalizationRulesetLoader() {
    }

    public static PersonalizationRuleset loadClasspath(String path) {
        String resource = path.startsWith("/") ? path : "/" + path;
        try (InputStream in = PersonalizationRulesetLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new PersonalizationRulesetException("Ruleset not found on classpath: " + path);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PersonalizationRulesetException("Cannot read ruleset " + path, e);
        }
    }

    public static PersonalizationRuleset parse(String yamlText) {
        String text = normalize(yamlText);
        JsonNode tree;
        try {
            tree = YAML.readTree(text);
        } catch (JsonProcessingException e) {
            throw new PersonalizationRulesetException("Invalid ruleset YAML: " + e.getOriginalMessage(), e);
        }
        Section root = Section.root(tree);

        String packId = root.text("pack_id");
        if (!PersonalizationRuleset.PACK_ID.equals(packId)) {
            throw new PersonalizationRulesetException("pack_id must be '" + PersonalizationRuleset.PACK_ID + "', got '"
                    + packId + "'");
        }
        String version = root.text("version");
        if (!version.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new PersonalizationRulesetException("version must match [a-z0-9][a-z0-9._-]{0,63}, got '" + version + "'");
        }
        LocalDate checkedAt = parseDate(root.text("checked_at"));
        String disclaimer = root.text("disclaimer");

        Section evidence = root.section("evidence");
        int maxValueChars = evidence.integer("max_value_chars", 1, 100);
        evidence.finish();

        Map<String, PersonalizationRuleset.CharClass> classes = parseClasses(root.section("char_classes"));

        Section printProfiles = root.section("print_profiles");
        String defaultProfile = printProfiles.text("default_profile");
        Map<String, PersonalizationRuleset.PrintProfile> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, Section> entry : printProfiles.sections("profiles").entrySet()) {
            String code = entry.getKey();
            if (!PROFILE_CODE.matcher(code).matches()) {
                throw new PersonalizationRulesetException("print_profiles.profiles key '" + code
                        + "' must match [a-z0-9][a-z0-9_-]{0,63}");
            }
            profiles.put(code, parseProfile(code, entry.getValue(), classes));
        }
        printProfiles.finish();
        if (!profiles.containsKey(defaultProfile)) {
            throw new PersonalizationRulesetException("print_profiles.default_profile '" + defaultProfile
                    + "' is not a declared profile (declared: " + profiles.keySet() + ")");
        }

        List<PersonalizationRuleset.RuleSpec> rules = parseRules(root.section("rules"));
        PersonalizationRuleset.TrademarkTemplates trademark = parseTrademark(root.section("trademark"));
        root.finish();

        return new PersonalizationRuleset(packId, version, checkedAt, sha256(text), disclaimer, maxValueChars,
                defaultProfile, profiles, rules, trademark);
    }

    /** LF line endings, no BOM. This is the text that gets hashed. */
    static String normalize(String text) {
        String withoutBom = text.startsWith("﻿") ? text.substring(1) : text;
        return withoutBom.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ---- character classes and profiles ----------------------------------------------------------------------------

    private static Map<String, PersonalizationRuleset.CharClass> parseClasses(Section section) {
        Map<String, PersonalizationRuleset.CharClass> classes = new LinkedHashMap<>();
        for (Map.Entry<String, Section> entry : section.children().entrySet()) {
            String name = entry.getKey();
            String where = "char_classes." + name;
            if (!CLASS_NAME.matcher(name).matches()) {
                throw new PersonalizationRulesetException(where + ": the name must match [a-z][a-z0-9_]{0,63}");
            }
            Section charClass = entry.getValue();
            String chars = charClass.optionalText("chars");
            List<String> rangeTexts = charClass.optionalStrings("ranges");
            if ((chars == null || chars.isEmpty()) && rangeTexts.isEmpty()) {
                throw new PersonalizationRulesetException(where + " must have 'chars' or 'ranges'");
            }
            Set<Integer> set = new LinkedHashSet<>();
            if (chars != null) {
                chars.codePoints().forEach(codePoint -> {
                    requireScalar(where, codePoint);
                    set.add(codePoint);
                });
            }
            List<int[]> ranges = new ArrayList<>();
            for (String rangeText : rangeTexts) {
                Matcher matcher = RANGE.matcher(rangeText.trim());
                if (!matcher.matches()) {
                    throw new PersonalizationRulesetException(where + ".ranges entry '" + rangeText
                            + "' must look like U+0041-U+005A");
                }
                int low = Integer.parseInt(matcher.group(1), 16);
                int high = Integer.parseInt(matcher.group(2), 16);
                requireScalar(where, low);
                requireScalar(where, high);
                if (low > high) {
                    throw new PersonalizationRulesetException(where + ".ranges entry '" + rangeText
                            + "' ends before it starts");
                }
                ranges.add(new int[] {low, high});
            }
            charClass.finish();
            classes.put(name, new PersonalizationRuleset.CharClass(name, set, ranges));
        }
        return classes;
    }

    private static void requireScalar(String where, int codePoint) {
        if (!Character.isValidCodePoint(codePoint) || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new PersonalizationRulesetException(where + " has a code point that is not a Unicode scalar value: "
                    + Integer.toHexString(codePoint));
        }
    }

    private static PersonalizationRuleset.PrintProfile parseProfile(String code, Section section,
            Map<String, PersonalizationRuleset.CharClass> classes) {
        String where = "print_profiles.profiles." + code;
        List<PersonalizationRuleset.CharClass> allowed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : section.strings("allowed_chars", true)) {
            PersonalizationRuleset.CharClass charClass = classes.get(name);
            if (charClass == null) {
                throw new PersonalizationRulesetException(where + ".allowed_chars refers to the class '" + name
                        + "' which char_classes does not declare (declared: " + classes.keySet() + ")");
            }
            if (!seen.add(name)) {
                throw new PersonalizationRulesetException(where + ".allowed_chars lists '" + name + "' twice");
            }
            allowed.add(charClass);
        }
        int maxLength = section.integer("max_length", 1, 1000);
        Map<String, Integer> byLabel = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : section.optionalIntegerMap("max_length_by_label", 1, 1000).entrySet()) {
            String key = Texts.labelKey(entry.getKey());
            if (key == null || byLabel.put(key, entry.getValue()) != null) {
                throw new PersonalizationRulesetException(where + ".max_length_by_label has a blank label or the label '"
                        + entry.getKey() + "' twice (labels are compared ignoring case)");
            }
        }
        boolean allowLowercase = section.bool("allow_lowercase");
        Source source = parseSource(section.section("source"), where + ".source");
        section.finish();
        return new PersonalizationRuleset.PrintProfile(code, allowed, maxLength, byLabel, allowLowercase, source);
    }

    // ---- rules and templates ---------------------------------------------------------------------------------------

    private static List<PersonalizationRuleset.RuleSpec> parseRules(Section rules) {
        List<String> declared = rules.keys();
        if (declared.size() != RuleIds.RULES.size() || !new HashSet<>(declared).equals(new HashSet<>(RuleIds.RULES))) {
            throw new PersonalizationRulesetException("rules must declare exactly " + RuleIds.RULES + " once each, got "
                    + declared);
        }
        List<PersonalizationRuleset.RuleSpec> specs = new ArrayList<>();
        for (String id : declared) {
            Section rule = rules.section(id);
            Source source = parseSource(rule.section("source"), "rules." + id + ".source");
            Section kinds = rule.section("kinds");
            Set<String> expected = RuleIds.KINDS.get(id);
            Map<String, Section> declaredKinds = kinds.children();
            if (!declaredKinds.keySet().equals(expected)) {
                throw new PersonalizationRulesetException("rules." + id + ".kinds must be exactly "
                        + expected.stream().sorted().toList() + ", got " + declaredKinds.keySet());
            }
            Map<String, PersonalizationRuleset.Kind> parsed = new LinkedHashMap<>();
            for (Map.Entry<String, Section> entry : declaredKinds.entrySet()) {
                String where = "rules." + id + ".kinds." + entry.getKey();
                Section kind = entry.getValue();
                Severity severity = parseSeverity(where, kind.text("severity"));
                String message = kind.text("message");
                String suggestion = kind.text("suggestion");
                Templates.validate(where + ".message", message, RuleIds.PLACEHOLDERS.get(id));
                Templates.validate(where + ".suggestion", suggestion, RuleIds.PLACEHOLDERS.get(id));
                kind.finish();
                parsed.put(entry.getKey(), new PersonalizationRuleset.Kind(severity, message, suggestion));
            }
            rule.finish();
            specs.add(new PersonalizationRuleset.RuleSpec(id, source, parsed));
        }
        rules.finish();
        return specs;
    }

    private static PersonalizationRuleset.TrademarkTemplates parseTrademark(Section section) {
        Source source = parseSource(section.section("source"), "trademark.source");
        String note = section.text("color_phrase_note");
        Templates.validate("trademark.color_phrase_note", note, Set.of());
        Section rules = section.section("rules");
        List<String> declared = rules.keys();
        if (declared.size() != RuleIds.TRADEMARK_RULES.size()
                || !new HashSet<>(declared).equals(new HashSet<>(RuleIds.TRADEMARK_RULES))) {
            throw new PersonalizationRulesetException("trademark.rules must declare exactly " + RuleIds.TRADEMARK_RULES
                    + " once each, got " + declared);
        }
        Map<String, PersonalizationRuleset.Template> templates = new LinkedHashMap<>();
        for (String id : declared) {
            Section rule = rules.section(id);
            String where = "trademark.rules." + id;
            String message = rule.text("message");
            String suggestion = rule.text("suggestion");
            Templates.validate(where + ".message", message, RuleIds.TRADEMARK_PLACEHOLDERS);
            Templates.validate(where + ".suggestion", suggestion, RuleIds.TRADEMARK_PLACEHOLDERS);
            rule.finish();
            templates.put(id, new PersonalizationRuleset.Template(message, suggestion));
        }
        rules.finish();
        section.finish();
        return new PersonalizationRuleset.TrademarkTemplates(source, note, templates);
    }

    /** Every block's source is {@code unverified}: this is internal print policy, not a marketplace or legal rule. */
    private static Source parseSource(Section section, String where) {
        SourceStatus status = SourceStatus.parse(where + ".status", section.text("status"));
        if (status != SourceStatus.UNVERIFIED) {
            throw new PersonalizationRulesetException(where + ".status must be 'unverified': nothing in this ruleset is "
                    + "backed by a marketplace or legal source (ADR-016)");
        }
        String note = section.optionalText("note");
        section.finish();
        return new Source(status, List.of(), note);
    }

    private static Severity parseSeverity(String where, String value) {
        Severity severity;
        try {
            severity = Severity.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new PersonalizationRulesetException(where + ".severity must be WARNING or INFO, got '" + value + "'");
        }
        if (severity == Severity.ERROR) {
            throw new PersonalizationRulesetException(where + ".severity cannot be ERROR: ERROR needs a verified source "
                    + "(ADR-006) and this ruleset has none");
        }
        return severity;
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new PersonalizationRulesetException("checked_at must be an ISO date (YYYY-MM-DD), got '" + value + "'", e);
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

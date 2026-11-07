package com.thanhdatpb.listingguard.personalization.config;

import com.thanhdatpb.listingguard.core.finding.Severity;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A loaded personalization ruleset (ADR-016): immutable, with its content hash. Everything a rule needs is a key of the
 * file: the character classes, the print profiles and their limits, the severities and the Vietnamese messages
 * (ADR-001). {@code contentHash} is the SHA-256 of the file text with LF line endings and no BOM (ADR-002).
 */
public record PersonalizationRuleset(String packId, String version, LocalDate checkedAt, String contentHash,
                                     String disclaimer, int maxValueChars, String defaultProfile,
                                     Map<String, PrintProfile> profiles, List<RuleSpec> rules,
                                     TrademarkTemplates trademark) {

    public static final String PACK_ID = "personalization-screening";

    /** A named set of code points. */
    public record CharClass(String name, Set<Integer> chars, List<int[]> ranges) {

        public CharClass {
            chars = Set.copyOf(chars);
            ranges = List.copyOf(ranges);
        }

        public boolean contains(int codePoint) {
            if (chars.contains(codePoint)) {
                return true;
            }
            for (int[] range : ranges) {
                if (codePoint >= range[0] && codePoint <= range[1]) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * What one printer or workshop can print. {@code maxLengthByLabel} is keyed by {@code Texts.labelKey} of the label.
     */
    public record PrintProfile(String code, List<CharClass> classes, int maxLength, Map<String, Integer> maxLengthByLabel,
                               boolean allowLowercase, Source source) {

        public PrintProfile {
            classes = List.copyOf(classes);
            maxLengthByLabel = Map.copyOf(maxLengthByLabel);
        }

        /** True when the workshop can print the code point (it is in one of the allowed classes). */
        public boolean allows(int codePoint) {
            for (CharClass charClass : classes) {
                if (charClass.contains(codePoint)) {
                    return true;
                }
            }
            return false;
        }

        /** The limit, in code points, for a value with this label key (null for no label). */
        public int limitFor(String labelKey) {
            if (labelKey == null) {
                return maxLength;
            }
            return maxLengthByLabel.getOrDefault(labelKey, maxLength);
        }
    }

    /** One case of a rule: its severity and its Vietnamese message and suggestion. */
    public record Kind(Severity severity, String message, String suggestion) {
    }

    public record RuleSpec(String id, Source source, Map<String, Kind> kinds) {

        public RuleSpec {
            kinds = Map.copyOf(kinds);
        }

        public Kind kind(String name) {
            Kind kind = kinds.get(name);
            if (kind == null) {
                throw new IllegalStateException("Rule " + id + " has no kind " + name);
            }
            return kind;
        }
    }

    public record Template(String message, String suggestion) {
    }

    /** The words of the trademark findings, by the rule id of the trademark scanner. */
    public record TrademarkTemplates(Source source, String colorPhraseNote, Map<String, Template> rules) {

        public TrademarkTemplates {
            rules = Map.copyOf(rules);
        }
    }

    public PersonalizationRuleset {
        profiles = Map.copyOf(profiles);
        rules = List.copyOf(rules);
    }

    public RuleSpec rule(String id) {
        return rules.stream().filter(rule -> rule.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Ruleset " + version + " has no rule " + id));
    }

    /**
     * The profile a request names; the default one when {@code code} is null or blank.
     *
     * @throws UnknownPrintProfileException if the ruleset does not declare the code
     */
    public PrintProfile profile(String code) {
        String wanted = code == null || code.isBlank() ? defaultProfile : code.strip();
        PrintProfile profile = profiles.get(wanted);
        if (profile == null) {
            throw new UnknownPrintProfileException(wanted, profileCodes());
        }
        return profile;
    }

    /** The declared profile codes, the default first and the rest in code order. */
    public List<String> profileCodes() {
        List<String> codes = new ArrayList<>();
        codes.add(defaultProfile);
        profiles.keySet().stream().filter(code -> !code.equals(defaultProfile)).sorted().forEach(codes::add);
        return List.copyOf(codes);
    }
}

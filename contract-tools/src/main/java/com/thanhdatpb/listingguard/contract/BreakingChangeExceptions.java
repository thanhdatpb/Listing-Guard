package com.thanhdatpb.listingguard.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The reviewed breaking changes of {@code docs/api/breaking-changes.yaml} (ADR-022, section 5). A breaking change passes the
 * compatibility check only when an entry here names its rule and location, gives a reason and names an ADR that mentions the
 * location.
 *
 * @param format     always {@value #FORMAT}
 * @param baseline   the release tag of the baseline the entries were written against
 * @param exceptions the declared breaking changes
 */
public record BreakingChangeExceptions(String format, String baseline, List<Entry> exceptions) {

    public static final String FORMAT = "listing-guard/breaking-changes/v1";

    private static final Pattern ADR_FILE = Pattern.compile("ADR-\\d{3}-[a-z0-9-]+\\.md");

    /**
     * @param rule     the rule id, as the report prints it
     * @param location the location, exactly as the report prints it
     * @param reason   why the change is needed and why no additive form works
     * @param adr      the file name of the ADR, in {@code docs/adr/}, that describes the change and names its location
     */
    public record Entry(@JsonProperty("rule") String rule, @JsonProperty("location") String location,
                        @JsonProperty("reason") String reason, @JsonProperty("adr") String adr) {

        String key() {
            return rule + " @ " + location;
        }
    }

    public BreakingChangeExceptions {
        exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
    }

    /** Reads the file strictly: an unknown member fails. */
    public static BreakingChangeExceptions load(Path file) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        try {
            return yaml.readValue(Files.readString(file, StandardCharsets.UTF_8), BreakingChangeExceptions.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** Whether an entry declares this change. */
    public Optional<Entry> declaring(Change change) {
        return exceptions.stream().filter(entry -> entry.key().equals(change.key())).findFirst();
    }

    /**
     * What is wrong, as messages for a person: a breaking change no entry declares, an entry that matches no breaking change, an
     * entry without a reason or ADR, and a wrong format or baseline. Empty when the check passes.
     */
    public List<String> problems(String baselineVersion, List<Change> changes, Path adrDirectory) {
        List<String> problems = new ArrayList<>();
        if (!FORMAT.equals(format)) {
            problems.add("breaking-changes.yaml: format must be " + FORMAT + ", found " + format);
        }
        if (!baselineVersion.equals(baseline)) {
            problems.add("breaking-changes.yaml: baseline is " + baseline + " but the baseline file is " + baselineVersion
                    + "; when the baseline moves to a new release, set baseline: " + baselineVersion + " and empty exceptions");
        }
        Set<String> seen = new HashSet<>();
        List<Change> breaking = changes.stream().filter(change -> change.changeClass() == ChangeClass.BREAKING).toList();
        for (Entry entry : exceptions) {
            String label = "breaking-changes.yaml entry " + entry.rule() + " @ " + entry.location();
            if (!seen.add(entry.key())) {
                problems.add(label + ": declared twice");
            }
            Optional<Rule> rule = Rule.byId(entry.rule() == null ? "" : entry.rule());
            if (rule.isEmpty()) {
                problems.add(label + ": unknown rule");
            } else if (rule.get().changeClass() != ChangeClass.BREAKING) {
                problems.add(label + ": " + entry.rule() + " is not a breaking rule and needs no exception");
            }
            if (breaking.stream().noneMatch(change -> change.key().equals(entry.key()))) {
                problems.add(label + ": matches no breaking change against " + baselineVersion + " (remove it)");
            }
            if (entry.reason() == null || entry.reason().isBlank()) {
                problems.add(label + ": reason is empty");
            }
            problems.addAll(adrProblems(label, entry, adrDirectory));
        }
        for (Change change : breaking) {
            if (declaring(change).isEmpty()) {
                problems.add("Breaking change not declared: " + change);
            }
        }
        return problems;
    }

    private static List<String> adrProblems(String label, Entry entry, Path adrDirectory) {
        if (entry.adr() == null || !ADR_FILE.matcher(entry.adr()).matches()) {
            return List.of(label + ": adr must be the file name of an ADR in docs/adr, for example ADR-022-api-contract-and-compatibility.md");
        }
        Path adr = adrDirectory.resolve(entry.adr());
        if (!Files.isRegularFile(adr)) {
            return List.of(label + ": " + adr + " does not exist");
        }
        try {
            if (entry.location() == null || !Files.readString(adr, StandardCharsets.UTF_8).contains(entry.location())) {
                return List.of(label + ": " + entry.adr() + " does not mention the location " + entry.location()
                        + "; amend the ADR to say what changes, why, and what a client must do");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.of();
    }
}

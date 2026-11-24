package com.thanhdatpb.listingguard.api.policymonitor;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PolicyImpact {
    private static final int MAX_APPROVAL_BYTES = 256 * 1024;
    public enum Status { PROPOSED, NEEDS_REVIEW, TEXT_ONLY, RULE_CHANGE, DEFERRED }
    public enum Reason { SOURCE_NOT_APPROVED, HASH_MISMATCH, EFFECTIVE_DATE_MISSING, INVALID_VERSION, SOURCE_ALERT, NONE }
    public enum Decision { TEXT_ONLY, RULE_CHANGE, DEFERRED }
    public record Approval(String oldHash, String newHash, LocalDate effectiveDate) { }
    public record Section(String type, String oldPath, String newPath, String oldHash, String newHash) { }
    public record Evidence(String title, String url, String oldVersion, String newVersion, String oldHash, String newHash,
                           Reason reason, List<Section> sections, List<PolicyDiffEngine.RuleReview> rules) { }

    private PolicyImpact() { }

    static Map<String, Approval> loadApprovals(Path file, ObjectMapper mapper) {
        try (var input = Files.newInputStream(file)) {
            byte[] data = input.readNBytes(MAX_APPROVAL_BYTES + 1);
            if (data.length > MAX_APPROVAL_BYTES) {
                throw new IllegalArgumentException("Policy impact approval file is too large");
            }
            return Map.copyOf(mapper.readerFor(new TypeReference<Map<String, Approval>>() { })
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(data));
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read policy impact approvals");
        }
    }

    static Reason reason(PolicyDiffEngine.DocumentChange change, Approval approval, boolean alert) {
        if (approval == null || !canonicalHttps(change.url())) {
            return Reason.SOURCE_NOT_APPROVED;
        }
        if (!Objects.equals(change.oldHash(), approval.oldHash()) || !Objects.equals(change.newHash(), approval.newHash())) {
            return Reason.HASH_MISMATCH;
        }
        if (!version(change.oldVersion()) || !version(change.newVersion())
                || (change.oldVersion() != null && change.newVersion() != null
                    && LocalDate.parse(change.newVersion()).isBefore(LocalDate.parse(change.oldVersion())))) {
            return Reason.INVALID_VERSION;
        }
        if (approval.effectiveDate() == null) {
            return Reason.EFFECTIVE_DATE_MISSING;
        }
        return alert ? Reason.SOURCE_ALERT : Reason.NONE;
    }

    static List<Section> sections(List<PolicyDiffEngine.SectionChange> changes) {
        var removed = changes.stream().filter(row -> "SECTION_REMOVED".equals(row.type())).toList();
        var added = changes.stream().filter(row -> "SECTION_ADDED".equals(row.type())).toList();
        var moved = new java.util.HashSet<PolicyDiffEngine.SectionChange>();
        var result = new ArrayList<Section>();
        for (var old : removed) {
            var matches = added.stream().filter(row -> old.oldHash().equals(row.newHash())).toList();
            if (matches.size() == 1 && removed.stream().filter(row -> old.oldHash().equals(row.oldHash())).count() == 1) {
                var target = matches.getFirst();
                moved.add(old);
                moved.add(target);
                result.add(new Section("SECTION_MOVED", old.path(), target.path(), old.oldHash(), target.newHash()));
            }
        }
        changes.stream().filter(row -> !moved.contains(row)).forEach(row -> result.add(new Section(row.type(),
                row.oldHash() == null ? null : row.path(), row.newHash() == null ? null : row.path(), row.oldHash(), row.newHash())));
        return List.copyOf(result);
    }

    private static boolean canonicalHttps(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean version(String value) {
        if (value == null) return true;
        try {
            return LocalDate.parse(value).toString().equals(value);
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}

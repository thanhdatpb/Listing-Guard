package com.thanhdatpb.listingguard.api.policymonitor;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.api.policymonitor.PolicyMonitorSnapshotStore.DocumentSnapshot;
import com.thanhdatpb.listingguard.api.policymonitor.PolicyMonitorSnapshotStore.SectionSnapshot;
import com.thanhdatpb.listingguard.rag.policy.ParsedPolicy;
import com.thanhdatpb.listingguard.rag.policy.PolicyManifest;
import com.thanhdatpb.listingguard.rag.policy.PolicyManifestLoader;
import com.thanhdatpb.listingguard.rag.policy.PolicyParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic, local-file-only comparison for the policy-diff job. */
public final class PolicyDiffEngine {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final PolicyMonitorSnapshotStore snapshots;
    private final Clock clock;

    public PolicyDiffEngine(PolicyMonitorSnapshotStore snapshots, Clock clock) {
        this.snapshots = snapshots;
        this.clock = clock;
    }

    public record SectionChange(String type, String path, String oldHash, String newHash, String excerpt) {}
    public record DocumentChange(String type, String url, String title, String oldVersion, String newVersion,
                                 String oldHash, String newHash, boolean documentHashChanged,
                                 List<SectionChange> sections) {
        public DocumentChange { sections = List.copyOf(sections); }
    }
    public record Alert(String code, String url, String message) {}
    public record RuleReview(String marketplace, String rulesetVersion, String ruleId, String sourceStatus,
                             String sourceUrl, String section) {}
    public record RuleSource(String name, String yaml) {}
    public record RunFile(Instant generatedAt, boolean dryRun, List<DocumentChange> changes, List<Alert> alerts,
                          List<RuleReview> rulesToReview) {
        public RunFile {
            changes = List.copyOf(changes);
            alerts = List.copyOf(alerts);
            rulesToReview = List.copyOf(rulesToReview);
        }
        public boolean empty() { return changes.isEmpty() && alerts.isEmpty() && rulesToReview.isEmpty(); }
    }

    /** Reads current local files, compares with the DB snapshot, and updates it only outside dry-run mode. */
    public RunFile run(Path manifestPath, Path rawDir, List<RuleSource> rulesets, int staleDays, boolean dryRun) {
        PolicyManifest manifest = PolicyManifestLoader.load(manifestPath);
        Map<String, DocumentSnapshot> before = new TreeMap<>();
        snapshots.readAll().forEach(doc -> before.put(doc.url(), doc));
        Map<String, CurrentDocument> current = new TreeMap<>();
        List<Alert> alerts = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        for (PolicyManifest.Entry entry : manifest.documents()) {
            Path file = rawDir.resolve(entry.marketplace().toLowerCase(Locale.ROOT)).resolve(entry.file()).normalize();
            Path marketplaceDir = rawDir.resolve(entry.marketplace().toLowerCase(Locale.ROOT)).normalize();
            if (!file.startsWith(marketplaceDir) || !Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Policy file is missing or outside raw directory: " + entry.marketplace() + "/" + entry.file());
            }
            try {
                byte[] bytes = Files.readAllBytes(file);
                ParsedPolicy parsed = PolicyParser.forFile(entry.file()).parse(bytes);
                Map<String, String> sectionText = new TreeMap<>();
                for (ParsedPolicy.Section section : parsed.sections()) {
                    String path = section.headingPath().isEmpty() ? entry.title()
                            : entry.title() + " > " + String.join(" > ", section.headingPath());
                    String text = String.join("\n", section.units());
                    sectionText.merge(path, text, (left, right) -> left.isEmpty() ? right : right.isEmpty() ? left : left + "\n" + right);
                }
                List<SectionSnapshot> sectionHashes = sectionText.entrySet().stream()
                        .map(item -> new SectionSnapshot(item.getKey(), sha256(item.getValue().getBytes(StandardCharsets.UTF_8))))
                        .toList();
                DocumentSnapshot snapshot = new DocumentSnapshot(entry.url(), entry.file(), entry.marketplace(), entry.title(),
                        entry.version(), entry.fetchedAt(), sha256(bytes), sectionHashes);
                current.put(entry.url(), new CurrentDocument(snapshot, sectionText));
                long age = ChronoUnit.DAYS.between(entry.fetchedAt(), today);
                if (age > staleDays) {
                    alerts.add(new Alert("FETCHED_AT_STALE", entry.url(), "Saved " + age + " days ago; configured limit is " + staleDays + " days."));
                }
                if (parsed.lastUpdated() != null && !entry.version().equals(parsed.lastUpdated().toString())) {
                    alerts.add(new Alert("VERSION_MISMATCH", entry.url(), "Manifest version " + entry.version()
                            + " differs from page date " + parsed.lastUpdated() + "."));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read policy file " + file, e);
            }
        }

        List<DocumentChange> changes = compare(before, current);
        for (String oldUrl : before.keySet()) {
            if (!current.containsKey(oldUrl)) {
                DocumentSnapshot old = before.get(oldUrl);
                changes.add(new DocumentChange("DOCUMENT_REMOVED", oldUrl, old.title(), old.version(), null,
                        old.documentHash(), null, true, old.sections().stream().map(section -> new SectionChange("SECTION_REMOVED",
                                section.path(), section.hash(), null, null)).toList()));
            }
        }
        changes.sort(Comparator.comparing(DocumentChange::url).thenComparing(DocumentChange::type));
        List<RuleReview> reviews = mapRules(changes, rulesets);
        if (!dryRun) {
            snapshots.replaceAll(current.values().stream().map(CurrentDocument::snapshot).toList());
        }
        return new RunFile(clock.instant().truncatedTo(ChronoUnit.SECONDS), dryRun, changes, alerts, reviews);
    }

    private List<DocumentChange> compare(Map<String, DocumentSnapshot> before, Map<String, CurrentDocument> current) {
        List<DocumentChange> changes = new ArrayList<>();
        for (Map.Entry<String, CurrentDocument> item : current.entrySet()) {
            String url = item.getKey();
            DocumentSnapshot now = item.getValue().snapshot();
            DocumentSnapshot old = before.get(url);
            if (old == null) {
                List<SectionChange> added = now.sections().stream().map(section -> new SectionChange("SECTION_ADDED", section.path(),
                        null, section.hash(), excerpt(item.getValue().sectionText().get(section.path())))).toList();
                changes.add(new DocumentChange("DOCUMENT_NEW", url, now.title(), null, now.version(), null, now.documentHash(), true, added));
                continue;
            }
            List<SectionChange> sectionChanges = sectionChanges(old, item.getValue());
            if (old.version().equals(now.version()) && old.documentHash().equals(now.documentHash()) && sectionChanges.isEmpty()) continue;
            String type = old.version().equals(now.version()) ? "DOCUMENT_CHANGED" : "VERSION_CHANGED";
            changes.add(new DocumentChange(type, url, now.title(), old.version(), now.version(), old.documentHash(), now.documentHash(),
                    !old.documentHash().equals(now.documentHash()), sectionChanges));
        }
        return changes;
    }

    private List<SectionChange> sectionChanges(DocumentSnapshot old, CurrentDocument now) {
        Map<String, String> oldHashes = new TreeMap<>();
        old.sections().forEach(section -> oldHashes.put(section.path(), section.hash()));
        Map<String, String> newHashes = new TreeMap<>();
        now.snapshot().sections().forEach(section -> newHashes.put(section.path(), section.hash()));
        List<SectionChange> result = new ArrayList<>();
        for (String path : oldHashes.keySet()) {
            if (!newHashes.containsKey(path)) result.add(new SectionChange("SECTION_REMOVED", path, oldHashes.get(path), null, null));
            else if (!oldHashes.get(path).equals(newHashes.get(path))) result.add(new SectionChange("SECTION_CHANGED", path,
                    oldHashes.get(path), newHashes.get(path), excerpt(now.sectionText().get(path))));
        }
        for (String path : newHashes.keySet()) {
            if (!oldHashes.containsKey(path)) result.add(new SectionChange("SECTION_ADDED", path, null, newHashes.get(path), excerpt(now.sectionText().get(path))));
        }
        result.sort(Comparator.comparing(SectionChange::path).thenComparing(SectionChange::type));
        return result;
    }

    private List<RuleReview> mapRules(List<DocumentChange> changes, List<RuleSource> rulesetFiles) {
        Map<String, List<DocumentChange>> changedByUrl = new TreeMap<>();
        for (DocumentChange change : changes) changedByUrl.computeIfAbsent(change.url(), ignored -> new ArrayList<>()).add(change);
        Map<String, RuleReview> result = new TreeMap<>();
        for (RuleSource ruleset : rulesetFiles) {
            try {
                JsonNode root = YAML.readTree(ruleset.yaml());
                String marketplace = root.path("marketplace").asText("");
                String version = root.path("version").asText(ruleset.name());
                JsonNode rules = root.path("rules");
                var fields = rules.fields();
                while (fields.hasNext()) {
                    var rule = fields.next();
                    JsonNode source = rule.getValue().path("source");
                    String status = source.path("status").asText("unknown");
                    List<String> urls = new ArrayList<>();
                    JsonNode urlsNode = source.path("urls");
                    if (urlsNode.isArray()) urlsNode.forEach(url -> urls.add(url.asText()));
                    else if (urlsNode.isTextual()) urls.add(urlsNode.asText());
                    String sourceSection = source.path("section").asText("");
                    for (Map.Entry<String, List<DocumentChange>> document : changedByUrl.entrySet()) {
                        if (!urls.stream().anyMatch(sourceUrl -> urlKey(sourceUrl).equals(urlKey(document.getKey())))) continue;
                        List<String> changedSections = document.getValue().stream().flatMap(change -> change.sections().stream())
                                .filter(change -> change.type().startsWith("SECTION_")).map(SectionChange::path).toList();
                        if (!sourceSection.isBlank() && !changedSections.stream().anyMatch(section -> section.equals(sourceSection)
                                || section.endsWith(" > " + sourceSection))) continue;
                        RuleReview review = new RuleReview(marketplace, version, rule.getKey(), status, document.getKey(),
                                sourceSection.isBlank() ? null : sourceSection);
                        result.put(marketplace + "/" + version + "/" + rule.getKey() + "/" + document.getKey(), review);
                    }
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read ruleset " + ruleset.name(), e);
            }
        }
        return List.copyOf(result.values());
    }

    private static String urlKey(String text) {
        try {
            java.net.URI uri = java.net.URI.create(text.trim()).normalize();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (RuntimeException e) {
            return text;
        }
    }

    private static String excerpt(String text) {
        if (text == null) return null;
        String flat = text.replaceAll("\\s+", " ").strip();
        int count = flat.codePointCount(0, flat.length());
        return count <= 200 ? flat : flat.substring(0, flat.offsetByCodePoints(0, 200));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record CurrentDocument(DocumentSnapshot snapshot, Map<String, String> sectionText) {}
}

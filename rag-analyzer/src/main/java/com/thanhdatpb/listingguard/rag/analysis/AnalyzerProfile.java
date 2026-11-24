package com.thanhdatpb.listingguard.rag.analysis;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.thanhdatpb.listingguard.core.listing.Listing;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** A packaged profile is selected explicitly by name and pinned to its original bytes. */
public final class AnalyzerProfile {
    private static final String ETSY_VERSION = "etsy-policy-2026-11";
    private static final String ETSY_HASH = "4bed1f38f3555b904995d62d2fc79a671aecf256097407e0aca0f97ecbf3361e";
    private static final int MAX_PROFILE_BYTES = 32_768;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Settings settings;
    private final String hash;

    private AnalyzerProfile(Settings settings, String hash) {
        this.settings = settings;
        this.hash = hash;
    }

    public static AnalyzerProfile etsy() {
        try (InputStream stream = AnalyzerProfile.class.getResourceAsStream("/analyzer/" + ETSY_VERSION + ".yaml")) {
            if (stream == null) {
                throw new IllegalStateException("Missing packaged analyzer profile");
            }
            return loadPinned(stream, ETSY_VERSION, ETSY_HASH);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load the packaged analyzer profile", e);
        }
    }

    static AnalyzerProfile loadPinned(InputStream stream, String version, String expectedHash) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_PROFILE_BYTES + 1);
        if (bytes.length > MAX_PROFILE_BYTES || !sha256(bytes).equals(expectedHash)) {
            throw new IllegalStateException("Analyzer profile does not match its pinned hash");
        }
        Settings settings = YAML.readValue(bytes, Settings.class);
        if (!settings.version().equals(version)) {
            throw new IllegalStateException("Analyzer profile version does not match its pinned version");
        }
        return new AnalyzerProfile(settings, expectedHash);
    }

    public Settings settings() {
        return settings;
    }

    public String hash() {
        return hash;
    }

    /** Copies only title, tags and description, bounded before copying potentially large fields. */
    public List<String> queries(Listing listing, String language) {
        List<String> probes = settings.probes().get(language);
        if (probes == null) {
            throw new IllegalArgumentException("Unsupported analyzer query language");
        }
        int titleAndTagsBudget = settings.listingChars() - settings.descriptionChars();
        StringBuilder titleAndTags = new StringBuilder(cut(listing.title(), titleAndTagsBudget));
        for (String tag : listing.tags().stream().limit(32).toList()) {
            int remaining = titleAndTagsBudget - titleAndTags.length() - 1;
            if (remaining <= 0) {
                break;
            }
            String bounded = cut(tag, remaining);
            if (!bounded.isEmpty()) {
                titleAndTags.append(' ').append(bounded);
            }
        }
        List<String> queries = new ArrayList<>(6);
        if (!titleAndTags.isEmpty()) {
            queries.add(titleAndTags.toString().strip());
        }
        String description = cut(listing.description(), settings.descriptionChars());
        if (!description.isEmpty()) {
            queries.add(description);
        }
        queries.addAll(probes);
        return List.copyOf(queries);
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        int end = Math.min(text.length(), max);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).strip();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record Source(String url, String version) {
        public Source {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || url.length() > 2_048) {
                throw new IllegalArgumentException("Invalid analyzer profile source URL");
            }
            LocalDate.parse(version);
        }
    }

    public record Settings(String version, String marketplace, String sourceLocale, int topK, int maxChunks,
                           int contextChars, int listingChars, int descriptionChars, int maxSnapshotAgeDays,
                           int minQuoteChars, int maxQuoteChars, List<String> topics, Map<String, List<String>> probes,
                           Map<String, Source> sources) {
        public Settings {
            if (version == null || !version.matches("[a-z0-9-]{1,64}") || !"ETSY".equals(marketplace)
                    || !"en".equals(sourceLocale) || topK < 1 || topK > 3 || maxChunks < 1 || maxChunks > 12
                    || contextChars < 1 || contextChars > 12_000 || listingChars < 2 || listingChars > 6_000
                    || descriptionChars < 1 || descriptionChars >= listingChars || maxSnapshotAgeDays < 1
                    || maxSnapshotAgeDays > 90 || minQuoteChars < 20 || maxQuoteChars < minQuoteChars
                    || maxQuoteChars > 400 || topics == null || topics.isEmpty() || sources == null || sources.isEmpty()
                    || sources.size() > 16 || probes == null || !probes.keySet().equals(Set.of("en", "vi"))
                    || probes.values().stream().anyMatch(list -> list.size() != 4
                        || list.stream().anyMatch(probe -> probe.isBlank() || probe.length() > 256))
                    || sources.keySet().stream().anyMatch(file -> !file.matches("[a-z0-9._-]{1,255}"))) {
                throw new IllegalArgumentException("Invalid analyzer profile settings");
            }
            topics = List.copyOf(topics);
            probes = probes.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                    entry -> List.copyOf(entry.getValue())));
            sources = Map.copyOf(sources);
        }
    }
}

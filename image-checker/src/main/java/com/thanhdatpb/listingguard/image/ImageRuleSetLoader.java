package com.thanhdatpb.listingguard.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Set;

/** Strict loader: no unknown rule data is silently ignored and unverified rules can never be ERROR. */
public final class ImageRuleSetLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private ImageRuleSetLoader() { }

    public static ImageRuleset loadClasspath(ImageMarketplace market) {
        String resource = "/rulesets/" + market.name().toLowerCase() + "-image-2026-11.yaml";
        try (InputStream stream = ImageRuleSetLoader.class.getResourceAsStream(resource)) {
            if (stream == null) throw new ImageCheckException("Missing ruleset " + resource);
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), market);
        } catch (IOException e) { throw new ImageCheckException("Could not read image ruleset", e); }
    }

    public static ImageRuleset parse(String yaml, ImageMarketplace expected) {
        try {
            String normalized = yaml.replace("\r\n", "\n").replace('\r', '\n');
            if (normalized.startsWith("\uFEFF")) normalized = normalized.substring(1);
            JsonNode root = YAML.readTree(normalized);
            require(root, "pack_id", "image-compliance");
            String prefix = expected.name().toLowerCase() + "-image-";
            String version = requiredText(root, "version");
            if (!version.startsWith(prefix)) throw new ImageCheckException("Ruleset version does not match marketplace");
            LocalDate checkedAt = LocalDate.parse(requiredText(root, "checked_at"));
            if (!root.path("marketplace").asText().equals(expected.name())) throw new ImageCheckException("Ruleset marketplace mismatch");
            JsonNode formats = root.path("supported_formats");
            if (!formats.isArray() || formats.isEmpty()) throw new ImageCheckException("supported_formats must be a non-empty list");
            for (JsonNode format : formats) if (!Set.of("JPEG", "PNG").contains(format.asText())) throw new ImageCheckException("Only JPEG and PNG are supported in v1");
            JsonNode limits = root.path("limits");
            if (!limits.path("max_encoded_bytes").canConvertToLong() || limits.path("max_encoded_bytes").asLong() < 1
                    || !limits.path("max_decoded_pixels").canConvertToLong() || limits.path("max_decoded_pixels").asLong() < 1)
                throw new ImageCheckException("Positive operational image safety limits are required");
            JsonNode rules = root.path("rules");
            if (!rules.isObject() || rules.isEmpty()) throw new ImageCheckException("Ruleset rules are required");
            Iterator<String> ids = rules.fieldNames();
            while (ids.hasNext()) {
                String id = ids.next();
                JsonNode rule = rules.path(id);
                JsonNode source = rule.path("source");
                String status = source.path("status").asText();
                if (!Set.of("verified", "secondary", "unverified").contains(status)) throw new ImageCheckException("Invalid source status for " + id);
                JsonNode urls = source.path("urls");
                if (urls.isMissingNode() || !urls.isArray() || urls.isEmpty()) throw new ImageCheckException("Source URLs required for " + id);
                for (JsonNode url : urls) if (!url.asText().startsWith("https://")) throw new ImageCheckException("Source URL must use HTTPS");
                Severity severity = Severity.valueOf(requiredText(rule, "severity"));
                if (!status.equals("verified") && severity == Severity.ERROR) throw new ImageCheckException("Unverified image rule cannot be ERROR: " + id);
                if (!rule.path("message").isTextual()) throw new ImageCheckException("Message required for " + id);
            }
            JsonNode summary = root.path("summary");
            JsonNode mappings = summary.path("by_severity");
            if (!mappings.isObject() || !mappings.path("ERROR").isTextual() || !mappings.path("WARNING").isTextual()
                    || !mappings.path("INFO").isTextual() || !mappings.path("NONE").isTextual()) throw new ImageCheckException("summary.by_severity must map every severity and NONE");
            if (!summary.path("incomplete").asText().equals("INCOMPLETE")) throw new ImageCheckException("Incomplete summary must remain INCOMPLETE");
            return new ImageRuleset("image-compliance", version, checkedAt, expected, sha256(normalized), root);
        } catch (ImageCheckException e) { throw e; }
        catch (Exception e) { throw new ImageCheckException("Invalid image ruleset", e); }
    }

    private static void require(JsonNode root, String key, String expected) {
        if (!root.path(key).asText().equals(expected)) throw new ImageCheckException("Invalid ruleset " + key);
    }
    private static String requiredText(JsonNode root, String key) {
        if (!root.path(key).isTextual() || root.path(key).asText().isBlank()) throw new ImageCheckException("Required ruleset field: " + key);
        return root.path(key).asText();
    }
    private static String sha256(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
}

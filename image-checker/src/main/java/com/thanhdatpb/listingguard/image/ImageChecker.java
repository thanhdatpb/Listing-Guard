package com.thanhdatpb.listingguard.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.thanhdatpb.listingguard.core.finding.Severity;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Deterministic image analysis core. It neither stores nor returns the submitted image data. */
public final class ImageChecker {
    private final ImageRuleset ruleset;
    private final JsonNode root;
    private final JsonNode profile;
    private final String profileName;
    private final ImageDecoder decoder = new ImageDecoder();

    public ImageChecker(ImageRuleset ruleset, String profileName) {
        this.ruleset = java.util.Objects.requireNonNull(ruleset, "ruleset");
        if (ruleset.document().path("marketplace").asText().equals("")) throw new ImageCheckException("Ruleset marketplace missing");
        this.root = ruleset.document();
        this.profileName = profileName;
        this.profile = root.path("profiles").path(profileName);
        if (profile.isMissingNode() || profile.isNull()) throw new ImageCheckException("Unknown image profile");
        ImageIoRuntime.cacheDisabled();
    }

    public ImageCheckResult check(ImageCheckInput input) {
        if (input.marketplace() != ruleset.marketplace()) throw new ImageCheckException("Marketplace does not match ruleset");
        if (!profileName.equals(input.profile())) throw new ImageCheckException("Input profile does not match checker profile");
        if (!root.path("profiles").has(input.profile())) throw new ImageCheckException("Unknown image profile");
        CanonicalImageInput canonical = CanonicalImageInput.of(input);
        String fingerprint = ImageAnalysisFingerprint.of(ruleset).value();
        List<ImageFinding> packFindings = new ArrayList<>();
        List<String> packFailed = new ArrayList<>();
        try { testImageCount(input.images().size(), packFindings, packFailed); }
        catch (RuntimeException e) { failed(packFailed, "image_count"); }
        List<ImageItemResult> results = new ArrayList<>();
        List<String> allFailed = new ArrayList<>(packFailed);
        for (int i = 0; i < input.images().size(); i++) {
            ImageItemResult result = checkOne(i, input.images().get(i));
            results.add(result);
            for (String failed : result.failedRules()) allFailed.add("images[" + i + "]." + failed);
        }
        boolean incomplete = !packFailed.isEmpty() || results.stream().anyMatch(r -> r.completeness() == ImageItemResult.Completeness.INCOMPLETE);
        String summary = summary(incomplete, packFindings, results);
        return new ImageCheckResult(canonical.hash(), ruleset.version(), fingerprint,
                incomplete ? ImageItemResult.Completeness.INCOMPLETE : ImageItemResult.Completeness.COMPLETE,
                summary, results, packFindings, List.copyOf(allFailed));
    }

    private ImageItemResult checkOne(int ordinal, ImageItem item) {
        List<ImageFinding> findings = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        byte[] bytes = item.bytesUnsafe();
        String hash = sha256(bytes);
        try {
            if (item.source() == ImageSource.CUSTOMER_CONTENT) findings.add(finding("customer_content", "declared source=CUSTOMER_CONTENT"));
            long maxBytes = root.path("limits").path("max_encoded_bytes").asLong(-1);
            long maxPixels = root.path("limits").path("max_decoded_pixels").asLong(-1);
            if (maxBytes < 0 || maxPixels < 1) failed(failed, "image_safety_limits");
            else if (bytes.length > maxBytes) failed(failed, "encoded_size_limit");
            if (!failed.isEmpty()) return incomplete(ordinal, hash, item, findings, failed);

            DecodedImage decoded;
            try { decoded = decoder.decode(bytes, stringArray(root.path("supported_formats")), maxPixels); }
            catch (IOException | RuntimeException e) { failed(failed, "image_decode"); return incomplete(ordinal, hash, item, findings, failed); }
            ImageMetadata metadata = decoded.metadata();
            BufferedImage image = decoded.image();
            runBoundaries("image_dimensions", image.getWidth(), image.getHeight(), profile.path("width"), profile.path("height"), findings, failed);
            runRatio(image.getWidth() / (double) image.getHeight(), profile.path("aspect_ratio"), findings, failed);
            runBackgroundAndOccupancy(image, item.role(), findings, failed);
            runBlur(image, findings, failed);
            return new ImageItemResult(ordinal, hash, item.role(), item.source(), failed.isEmpty() ? ImageItemResult.Completeness.COMPLETE : ImageItemResult.Completeness.INCOMPLETE,
                    metadata, findings, failed);
        } catch (RuntimeException e) {
            failed(failed, "rule_execution");
            return incomplete(ordinal, hash, item, findings, failed);
        }
    }

    private void testImageCount(int count, List<ImageFinding> findings, List<String> failed) {
        JsonNode cfg = profile.path("image_count");
        boolean ran = false;
        if (number(cfg, "min") == null) failed(failed, "image_count");
        else { ran = true; if (count < number(cfg, "min")) findings.add(finding("image_count", "count=" + count)); }
        if (number(cfg, "max") == null) failed(failed, "image_count");
        else { ran = true; if (count > number(cfg, "max")) findings.add(finding("image_count", "count=" + count)); }
        if (!ran && !failed.contains("image_count")) failed(failed, "image_count");
    }

    private void runBoundaries(String rule, double width, double height, JsonNode w, JsonNode h, List<ImageFinding> findings, List<String> failed) {
        checkOneBoundary(rule, width, w, findings, failed);
        checkOneBoundary(rule, height, h, findings, failed);
    }
    private void checkOneBoundary(String rule, double value, JsonNode cfg, List<ImageFinding> findings, List<String> failed) {
        boolean ran = false;
        Double min = number(cfg, "min"), max = number(cfg, "max");
        if (min == null) failed(failed, rule); else { ran = true; if (value < min) findings.add(finding(rule, "value=" + value)); }
        if (max == null) failed(failed, rule); else { ran = true; if (value > max) findings.add(finding(rule, "value=" + value)); }
        if (!ran && !failed.contains(rule)) failed(failed, rule);
    }
    private void runRatio(double value, JsonNode cfg, List<ImageFinding> findings, List<String> failed) {
        Double min = number(cfg, "min"), max = number(cfg, "max");
        if (min == null) failed(failed, "aspect_ratio"); else if (value < min) findings.add(finding("aspect_ratio", "ratio=" + value));
        if (max == null) failed(failed, "aspect_ratio"); else if (value > max) findings.add(finding("aspect_ratio", "ratio=" + value));
    }

    private void runBackgroundAndOccupancy(BufferedImage image, ImageRole role, List<ImageFinding> findings, List<String> failed) {
        JsonNode bg = profile.path("main_background");
        JsonNode occ = profile.path("occupancy");
        if (ruleset.marketplace() == ImageMarketplace.AMAZON && role == ImageRole.MAIN) {
            Double tolerance = number(bg, "edge_rgb_tolerance"), minWhite = number(bg, "min_white_ratio");
            if (tolerance == null || minWhite == null) failed(failed, "main_background");
            else {
                long total = 0, white = 0;
                for (int x = 0; x < image.getWidth(); x++) { if (nearWhite(image.getRGB(x, 0), tolerance)) white++; total++; if (image.getHeight() > 1) { if (nearWhite(image.getRGB(x, image.getHeight() - 1), tolerance)) white++; total++; } }
                for (int y = 1; y < image.getHeight() - 1; y++) { if (nearWhite(image.getRGB(0, y), tolerance)) white++; total++; if (image.getWidth() > 1) { if (nearWhite(image.getRGB(image.getWidth() - 1, y), tolerance)) white++; total++; } }
                if (total > 0 && white / (double) total < minWhite) findings.add(finding("main_background", "edge_white_ratio=" + white / (double) total));
            }
        }
        Double occupancyMin = number(occ, "min_ratio"), whiteTolerance = number(occ, "white_rgb_tolerance");
        if (occupancyMin == null || whiteTolerance == null) failed(failed, "occupancy");
        else {
            int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if (!nearWhite(image.getRGB(x, y), whiteTolerance)) {
                minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
            }
            double ratio = maxX < 0 ? 0 : (double) (maxX - minX + 1) * (maxY - minY + 1) / ((long) image.getWidth() * image.getHeight());
            if (ratio < occupancyMin) findings.add(finding("occupancy", "bounding_box_ratio=" + ratio));
        }
    }

    private void runBlur(BufferedImage image, List<ImageFinding> findings, List<String> failed) {
        Double threshold = number(profile.path("blur"), "min_laplacian_variance");
        if (threshold == null) { failed(failed, "blur"); return; }
        if (image.getWidth() < 3 || image.getHeight() < 3) { failed(failed, "blur"); return; }
        double sum = 0, sumSquares = 0;
        long n = 0;
        for (int y = 1; y < image.getHeight() - 1; y++) for (int x = 1; x < image.getWidth() - 1; x++) {
            double center = gray(image.getRGB(x, y));
            double lap = gray(image.getRGB(x - 1, y)) + gray(image.getRGB(x + 1, y)) + gray(image.getRGB(x, y - 1)) + gray(image.getRGB(x, y + 1)) - 4 * center;
            sum += lap; sumSquares += lap * lap; n++;
        }
        double variance = sumSquares / n - (sum / n) * (sum / n);
        if (variance < threshold) findings.add(finding("blur", "laplacian_variance=" + variance));
    }

    private String summary(boolean incomplete, List<ImageFinding> globals, List<ImageItemResult> items) {
        if (incomplete) return root.path("summary").path("incomplete").asText("INCOMPLETE");
        Severity highest = null;
        for (ImageFinding f : globals) highest = higher(highest, f.severity());
        for (ImageItemResult item : items) for (ImageFinding f : item.findings()) highest = higher(highest, f.severity());
        String key = highest == null ? "NONE" : highest.name();
        String value = root.path("summary").path("by_severity").path(key).asText(null);
        if (value == null) throw new ImageCheckException("No summary mapping for " + key);
        return value;
    }
    private ImageFinding finding(String id, String evidence) {
        JsonNode rule = root.path("rules").path(id);
        if (rule.isMissingNode()) throw new ImageCheckException("No configured rule " + id);
        return new ImageFinding(id, Severity.valueOf(rule.path("severity").asText()), rule.path("message").asText(), evidence);
    }
    private static Severity higher(Severity a, Severity b) {
        if (a == null) return b;
        return rank(a) >= rank(b) ? a : b;
    }
    private static int rank(Severity s) { return switch (s) { case ERROR -> 3; case WARNING -> 2; case INFO -> 1; }; }
    private static boolean nearWhite(int argb, double tolerance) {
        int a = argb >>> 24, r = argb >>> 16 & 255, g = argb >>> 8 & 255, b = argb & 255;
        r = (r * a + 255 * (255 - a) + 127) / 255; g = (g * a + 255 * (255 - a) + 127) / 255; b = (b * a + 255 * (255 - a) + 127) / 255;
        return r >= 255 - tolerance && g >= 255 - tolerance && b >= 255 - tolerance;
    }
    private static double gray(int argb) { return .2126 * (argb >>> 16 & 255) + .7152 * (argb >>> 8 & 255) + .0722 * (argb & 255); }
    private static Double number(JsonNode node, String key) { JsonNode v = node.path(key); return v.isNumber() ? v.doubleValue() : null; }
    private static void failed(List<String> list, String rule) { if (!list.contains(rule)) list.add(rule); }
    private static String[] stringArray(JsonNode node) { List<String> values = new ArrayList<>(); node.forEach(v -> values.add(v.asText())); return values.toArray(String[]::new); }
    private static ImageItemResult incomplete(int index, String hash, ImageItem item, List<ImageFinding> findings, List<String> failed) {
        return new ImageItemResult(index, hash, item.role(), item.source(), ImageItemResult.Completeness.INCOMPLETE, null, findings, failed);
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 not available", e); }
    }
}

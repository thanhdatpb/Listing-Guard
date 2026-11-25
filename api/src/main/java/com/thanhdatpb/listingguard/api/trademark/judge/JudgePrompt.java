package com.thanhdatpb.listingguard.api.trademark.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/**
 * The prompt of the trademark context judge, read from a versioned resource (ADR-036 item 9). The version and the first 12 hex
 * characters of the SHA-256 of the file are part of the analysis version, so editing the file without a new version still misses
 * the cache.
 *
 * <p>Two versions exist and both are immutable (ADR-051): {@value #DEFAULT_VERSION}, the prompt of ADR-036, and
 * {@value #EVIDENCE_VERSION}, which asks the same question and puts an evidence pack the program computed in front of the sentence.
 * {@code listing-guard.llm.judge.prompt-version} picks one; the default is the old one, so a deployment changes nothing until it asks.
 *
 * @param version  the {@code version} member of the file
 * @param hash     SHA-256 of the file bytes, hex
 * @param evidence true when the prompt has an {@code {evidence}} block, so the program gathers an evidence pack for it
 */
public record JudgePrompt(String version, String hash, String system, String user, String schema, boolean evidence) {

    public static final String DEFAULT_VERSION = "trademark-judge-2026-11";
    public static final String EVIDENCE_VERSION = "trademark-judge-2026-12";
    public static final List<String> VERSIONS = List.of(DEFAULT_VERSION, EVIDENCE_VERSION);

    public static final String RESOURCE = "llm/" + DEFAULT_VERSION + ".yaml";

    public static JudgePrompt load() {
        return load(RESOURCE);
    }

    /**
     * The prompt of a version named in the settings.
     *
     * @throws IllegalArgumentException if no prompt has that version: the API does not start (ADR-012)
     */
    public static JudgePrompt forVersion(String version) {
        if (!VERSIONS.contains(version)) {
            throw new IllegalArgumentException("listing-guard.llm.judge.prompt-version must be one of " + VERSIONS + ", not '"
                    + version + "'.");
        }
        JudgePrompt prompt = load("llm/" + version + ".yaml");
        if (!prompt.version().equals(version)) {
            throw new IllegalStateException("The judge prompt file of " + version + " says it is " + prompt.version());
        }
        return prompt;
    }

    public static JudgePrompt load(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            Map<?, ?> yaml = new ObjectMapper(new YAMLFactory()).readValue(bytes, Map.class);
            return new JudgePrompt(text(yaml, "version"), sha256(bytes), text(yaml, "system"), text(yaml, "user"), text(yaml, "schema"),
                    Boolean.TRUE.equals(yaml.get("evidence")));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the judge prompt " + resource, e);
        }
    }

    /** The part of the analysis version this prompt owns: {@code <version>:<12 hex>}. */
    public String identity() {
        return version + ":" + hash.substring(0, 12);
    }

    /** The user message with the values of one finding put in; each placeholder is replaced once, in one pass. */
    public String render(String mark, String classes, String field, String text) {
        return render(mark, classes, field, text, "");
    }

    /**
     * The same with the evidence block of a prompt that has one ({@code {evidence}}); a prompt without that placeholder ignores it,
     * so the text of the version of ADR-036 is the one it always was.
     */
    public String render(String mark, String classes, String field, String text, String evidenceBlock) {
        Map<String, String> values = Map.of("{mark}", mark, "{classes}", classes, "{field}", field, "{text}", text,
                "{evidence}", evidenceBlock);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < user.length()) {
            String hit = null;
            if (user.charAt(i) == '{') {
                for (String placeholder : values.keySet()) {
                    if (user.startsWith(placeholder, i)) {
                        hit = placeholder;
                        break;
                    }
                }
            }
            if (hit == null) {
                out.append(user.charAt(i++));
            } else {
                out.append(values.get(hit));
                i += hit.length();
            }
        }
        return out.toString();
    }

    private static String text(Map<?, ?> yaml, String key) {
        Object value = yaml.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("The judge prompt has no '" + key + "'");
        }
        return text.stripTrailing();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

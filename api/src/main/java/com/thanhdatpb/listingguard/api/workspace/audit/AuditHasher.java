package com.thanhdatpb.listingguard.api.workspace.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The hashes the audit compares (ADR-061). {@link #input} is the SHA-256 of the standard input of a listing as the checks see it: keys in code
 * point order, texts trimmed and in NFC, empty values left out, so the same listing written twice in another key order has one hash.
 * {@link #analysis} is the SHA-256 of the parts of the analysis context that decide a result: when it is the same for a listing, and so is
 * the input hash, a new audit would say what the last one said.
 */
public final class AuditHasher {

    private AuditHasher() {
    }

    public static String input(JsonNode fields) {
        StringBuilder canonical = new StringBuilder();
        write(canonical, fields);
        return sha256(canonical.toString());
    }

    public static String analysis(Collection<String> parts) {
        return sha256(String.join("\u001f", parts));
    }

    private static boolean empty(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.isTextual()) {
            return node.asText().isBlank();
        }
        return (node.isArray() || node.isObject()) && node.isEmpty();
    }

    private static void write(StringBuilder out, JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!empty(e.getValue())) {
                    sorted.put(e.getKey(), e.getValue());
                }
            }
            out.append('{');
            sorted.forEach((key, value) -> {
                out.append(key.length()).append(':').append(key).append('=');
                write(out, value);
                out.append(';');
            });
            out.append('}');
        } else if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> {
                if (!empty(item)) {
                    items.add(item);
                }
            });
            out.append('[');
            items.forEach(item -> {
                write(out, item);
                out.append(',');
            });
            out.append(']');
        } else if (node.isTextual()) {
            String text = Normalizer.normalize(node.asText(), Normalizer.Form.NFC).strip();
            out.append('"').append(text.length()).append(':').append(text).append('"');
        } else {
            out.append(node.asText());
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

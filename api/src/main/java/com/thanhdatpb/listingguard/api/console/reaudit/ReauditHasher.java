package com.thanhdatpb.listingguard.api.console.reaudit;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The hash of one listing row (ADR-035): SHA-256 of the canonical form of the standard input the checks were given. The canonical form
 * has the keys of every object in code point order, no blank around a text, Unicode NFC, and no blank or {@code null} member, so the
 * same listing read from a file twice, with its columns in another order or an empty cell where the other had none, has one hash. The
 * hash is what the next run compares; the text it came from is never stored (ADR-010 stores the same kind of hash for a check).
 */
final class ReauditHasher {

    private ReauditHasher() {
    }

    static String hash(JsonNode listing) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, listing);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static String canonical(JsonNode listing) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, listing);
        return canonical.toString();
    }

    private static boolean empty(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return true;
        }
        return node.isTextual() && node.asText().isBlank();
    }

    private static void append(StringBuilder out, JsonNode node) {
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> members = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!empty(field.getValue())) {
                    members.add(field);
                }
            }
            Collections.sort(members, (a, b) -> a.getKey().compareTo(b.getKey()));
            out.append('{');
            for (int i = 0; i < members.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                text(out, members.get(i).getKey());
                out.append(':');
                append(out, members.get(i).getValue());
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode item : node) {
                if (empty(item)) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                append(out, item);
            }
            out.append(']');
        } else if (node.isTextual()) {
            text(out, node.asText());
        } else {
            out.append(node.toString());
        }
    }

    private static void text(StringBuilder out, String value) {
        String clean = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        out.append('"');
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}

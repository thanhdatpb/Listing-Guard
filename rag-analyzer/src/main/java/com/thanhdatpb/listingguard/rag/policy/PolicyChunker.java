package com.thanhdatpb.listingguard.rag.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cuts a parsed policy into chunks along its own structure: one chunk per section. A section longer
 * than {@code maxChars} is split between its paragraphs (never inside one), every part keeping the
 * section's headings. A tiny section is merged into a neighbouring sibling under the same parent.
 */
public final class PolicyChunker {

    /** Bump when the chunking rules change, so stored documents get re-chunked (see ADR-003). */
    public static final String VERSION = "chunker-1";

    /** Sections this deep or deeper (title counts as level 1) may be merged when tiny. */
    private static final int MIN_MERGE_DEPTH = 3;

    public record Config(int maxChars, int minChars) {

        public Config {
            if (minChars < 0 || maxChars <= minChars) {
                throw new IllegalArgumentException("Need 0 <= minChars < maxChars, got " + minChars + " and " + maxChars);
            }
        }
    }

    private static final class Draft {
        final List<String> path;
        final List<String> headingPath;
        final String anchor;
        final boolean split;
        final int part;
        final int parts;
        String content;
        final List<String> mergedHeadings = new ArrayList<>();

        Draft(List<String> path, List<String> headingPath, String anchor, String content, boolean split, int part, int parts) {
            this.path = path;
            this.headingPath = headingPath;
            this.anchor = anchor;
            this.content = content;
            this.split = split;
            this.part = part;
            this.parts = parts;
        }

        String lastHeading() {
            return path.get(path.size() - 1);
        }

        List<String> parent() {
            return path.subList(0, path.size() - 1);
        }
    }

    private final Config config;

    public PolicyChunker(Config config) {
        this.config = config;
    }

    public List<PolicyChunk> chunk(String documentTitle, ParsedPolicy policy) {
        List<Draft> drafts = new ArrayList<>();
        for (ParsedPolicy.Section section : policy.sections()) {
            List<String> units = section.units().stream().filter(unit -> !unit.isBlank()).toList();
            if (units.isEmpty()) {
                continue;
            }
            List<String> path = new ArrayList<>();
            path.add(documentTitle);
            path.addAll(section.headingPath());
            List<String> parts = pack(units);
            for (int i = 0; i < parts.size(); i++) {
                drafts.add(new Draft(path, section.headingPath(), section.anchor(), parts.get(i), parts.size() > 1, i + 1, parts.size()));
            }
        }
        mergeTinySections(drafts);

        List<PolicyChunk> chunks = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            chunks.add(new PolicyChunk(i, String.join(" > ", draft.path), draft.headingPath, draft.anchor, draft.content,
                    sha256(draft.content), metadata(draft)));
        }
        return chunks;
    }

    /** Groups consecutive units into parts of at most maxChars; a single unit longer than that stays whole. */
    private List<String> pack(List<String> units) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.length() > 0 && current.length() + 2 + unit.length() > config.maxChars()) {
                parts.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(unit);
        }
        parts.add(current.toString());
        return parts;
    }

    private void mergeTinySections(List<Draft> drafts) {
        for (int i = 0; i < drafts.size(); i++) {
            Draft tiny = drafts.get(i);
            if (tiny.split || tiny.content.length() >= config.minChars() || tiny.path.size() < MIN_MERGE_DEPTH) {
                continue;
            }
            Draft previous = i > 0 ? drafts.get(i - 1) : null;
            if (previous != null && canMerge(previous, tiny)) {
                previous.content = previous.content + "\n\n" + withHeading(tiny);
                previous.mergedHeadings.add(tiny.lastHeading());
                drafts.remove(i--);
                continue;
            }
            Draft next = i + 1 < drafts.size() ? drafts.get(i + 1) : null;
            if (next != null && canMerge(next, tiny)) {
                next.content = withHeading(tiny) + "\n\n" + next.content;
                next.mergedHeadings.add(0, tiny.lastHeading());
                drafts.remove(i--);
            }
        }
    }

    private boolean canMerge(Draft target, Draft tiny) {
        return !target.split
                && target.parent().equals(tiny.parent())
                && target.content.length() + 2 + withHeading(tiny).length() <= config.maxChars();
    }

    private static String withHeading(Draft draft) {
        return draft.lastHeading() + "\n" + draft.content;
    }

    private static Map<String, Object> metadata(Draft draft) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("heading_path", draft.headingPath);
        if (draft.split) {
            metadata.put("part", draft.part);
            metadata.put("parts", draft.parts);
        }
        if (!draft.mergedHeadings.isEmpty()) {
            metadata.put("merged_headings", List.copyOf(draft.mergedHeadings));
        }
        return metadata;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

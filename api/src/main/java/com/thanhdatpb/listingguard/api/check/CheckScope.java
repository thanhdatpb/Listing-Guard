package com.thanhdatpb.listingguard.api.check;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * How much of a listing a check covers (ADR-052). {@link Kind#FULL} is the check as it always was. {@link Kind#CONTENT} is for a table
 * that never had some listing fields (a Sheet with title, tags and description and no category, price or quantity column): the presence
 * of the fields outside {@link #checkedFields()} is left out, and the result says so. A field the table has and left empty is still
 * reported, and every other rule is unchanged.
 *
 * <p>The scope is part of what identifies a stored result. A content result carries it, with the fields it left out, in its
 * {@code analysisVersion} (see {@link #analysisVersion}), so it is never answered for a full check and a full result is never answered
 * for a content one. {@code POST /api/v1/checks} has no scope: it is {@code FULL}.
 *
 * @param checkedFields the listing fields the table has (the keys of the standard input); empty and unused for {@link Kind#FULL}
 */
public record CheckScope(Kind kind, Set<String> checkedFields) {

    public enum Kind {
        FULL, CONTENT
    }

    /** The longest {@code analysis_version} the table holds. */
    private static final int ANALYSIS_VERSION_MAX = 128;

    public static final CheckScope FULL = new CheckScope(Kind.FULL, Set.of());

    public CheckScope {
        checkedFields = kind == Kind.CONTENT ? Set.copyOf(checkedFields) : Set.of();
    }

    public static CheckScope content(Set<String> checkedFields) {
        return new CheckScope(Kind.CONTENT, checkedFields);
    }

    public boolean content() {
        return kind == Kind.CONTENT;
    }

    /**
     * The {@code analysisVersion} of a result of this scope: the ruleset's version for a full check (unchanged), and for a content check
     * that version, {@code +content} and the fields left out, so two content results are the same only when they left out the same
     * fields. A very long list is replaced by a digest of it.
     */
    String analysisVersion(String rulesetVersion, List<String> skippedFields) {
        if (!content()) {
            return rulesetVersion;
        }
        String base = rulesetVersion + "+content";
        if (skippedFields.isEmpty()) {
            return base;
        }
        String listed = base + ":" + String.join(",", skippedFields);
        return listed.length() <= ANALYSIS_VERSION_MAX ? listed : base + ":sha256-" + digest(String.join(",", skippedFields));
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

package com.thanhdatpb.listingguard.rag.policy;

import java.util.List;
import java.util.Map;

/**
 * One retrievable piece of a policy document.
 *
 * @param ordinal     position within the document, from 0
 * @param sectionPath document title and headings joined with {@code " > "}
 * @param headingPath headings without the document title
 * @param anchor      {@code id} of the nearest heading that has one, or null
 * @param content     the text as written, never altered
 * @param contentHash SHA-256 of {@code content}
 * @param metadata    extra structure: {@code heading_path}, and {@code part}/{@code parts} when a long section
 *                    was split, and {@code merged_headings} when tiny sibling sections were merged
 */
public record PolicyChunk(
        int ordinal,
        String sectionPath,
        List<String> headingPath,
        String anchor,
        String content,
        String contentHash,
        Map<String, Object> metadata) {

    public PolicyChunk {
        headingPath = List.copyOf(headingPath);
        metadata = Map.copyOf(metadata);
    }

    /** The text that gets embedded: the section path gives the content its context. */
    public String embeddingText() {
        return sectionPath + "\n" + content;
    }
}

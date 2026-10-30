package com.thanhdatpb.listingguard.rag.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Self-written policy text and listings: tests never use the real corpus. */
final class AnalysisFixtures {

    static final Set<String> TOPICS = Set.of("production_partner", "ai_disclosure", "mockup_images",
            "prohibited_item", "ip_infringement", "mature_content", "other");
    static final Set<String> FIELDS = Set.of("title", "description", "tags", "materials", "who_made", "when_made",
            "category");

    static final int MIN_QUOTE = 20;

    /** Newline, NBSP, em and en dashes, curly quotes: the shapes a page has and a model rewrites. */
    static final RetrievedChunk PARTNER = chunk(1, "seller.html", "Seller > Selling Basics",
            "Items must be made, designed, handpicked or sourced by you.\n"
                    + "If a production partner helps make your items, you must say so — “clearly”"
                    + " – in the listing.");

    static final RetrievedChunk MOCKUP = chunk(2, "images.html", "Images > Rules",
            "Listing photos must show the item that the buyer will receive. "
                    + "Mockups are not allowed for ﬁnished goods.");

    static final RetrievedChunk STRAIGHT = chunk(3, "labels.html", "Labels > AI",
            "Don't use \"AI\" labels - they are misleading to buyers.");

    static final List<RetrievedChunk> RETRIEVED = List.of(PARTNER, MOCKUP, STRAIGHT);

    static final Map<String, List<String>> LISTING = Map.of(
            "title", List.of("Sunset Mug"),
            "description", List.of("Printed and shipped by our print partner.\nMade by me!"),
            "tags", List.of("mug", "sunset gift"));

    private static final ObjectMapper JSON = new ObjectMapper();

    private AnalysisFixtures() {
    }

    static RetrievedChunk chunk(long id, String sourceFile, String sectionPath, String content) {
        return new RetrievedChunk(id, 0.9, "ETSY", "Doc " + id, sourceFile, sectionPath,
                "https://example.test/" + sourceFile + "#s" + id, "2026-01-0" + id, content);
    }

    static ProposedFinding proposed(long chunkId, String quote, String field, String value) {
        return new ProposedFinding("policy.production_partner", Severity.WARNING,
                field, value, "Thong bao", "Goi y", chunkId, quote);
    }

    static ParseResult parsed(ProposedFinding... findings) {
        return new ParseResult(List.of(findings), findings.length);
    }

    /** A finding object that matches the schema, as a map so a test can break one key. */
    static Map<String, Object> finding(Object chunkId, String quote) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("ruleId", "policy.production_partner");
        f.put("severity", "WARNING");
        f.put("field", "description");
        f.put("value", "print partner");
        f.put("message", "Danh sach noi ro doi tac in.");
        f.put("suggestion", "Ghi ro doi tac in trong mo ta.");
        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("chunkId", chunkId);
        citation.put("quote", quote);
        f.put("citation", citation);
        return f;
    }

    static String findingsJson(Object... findings) {
        try {
            return JSON.writeValueAsString(Map.of("findings", List.of(findings)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

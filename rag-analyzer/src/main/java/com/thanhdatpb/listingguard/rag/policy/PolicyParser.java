package com.thanhdatpb.listingguard.rag.policy;

import java.util.Locale;

/** Reads one policy file format into a {@link ParsedPolicy}. */
public interface PolicyParser {

    ParsedPolicy parse(byte[] content);

    /** Picks the parser by file extension. */
    static PolicyParser forFile(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) {
            return new HtmlPolicyParser();
        }
        if (name.endsWith(".pdf")) {
            return new PdfPolicyParser();
        }
        throw new PolicyParseException("Unsupported policy file type (expected .html, .htm or .pdf): " + fileName);
    }
}

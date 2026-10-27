package com.thanhdatpb.listingguard.rag.retrieval;

import java.util.List;

/** Finds the policy passages most relevant to a question, within one marketplace. */
public interface PolicyRetriever {

    /**
     * Only chunks of {@code marketplace} are considered at all: the marketplace filter is applied before the
     * vector ranking, so another marketplace's text can never displace or appear among the results.
     *
     * @param topK how many chunks to return, at most; fewer come back if the marketplace has fewer chunks
     * @return best match first
     * @throws IllegalArgumentException if marketplace or query is blank or topK is out of range
     */
    List<RetrievedChunk> search(String marketplace, String query, int topK);

    /**
     * @param score       cosine similarity, higher is closer (1.0 identical direction)
     * @param document    document title
     * @param sourceFile  file name from the manifest, a stable key of the document
     * @param sectionPath document title and headings joined with {@code " > "}
     * @param url         public URL of the document, with the section anchor when the page has one
     * @param version     the document's "Last updated" date
     */
    record RetrievedChunk(
            long chunkId,
            double score,
            String marketplace,
            String document,
            String sourceFile,
            String sectionPath,
            String url,
            String version,
            String content) {
    }
}

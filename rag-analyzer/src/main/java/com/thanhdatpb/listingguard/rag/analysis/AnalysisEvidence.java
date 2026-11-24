package com.thanhdatpb.listingguard.rag.analysis;

import com.thanhdatpb.listingguard.rag.retrieval.PolicyRetriever.RetrievedChunk;
import java.time.LocalDate;

/** One chunk and its trusted document metadata from the same retrieval snapshot. */
public record AnalysisEvidence(RetrievedChunk chunk, long documentId, String sourceHash, LocalDate fetchedAt,
                               String sourceLocale) { }

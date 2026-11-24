package com.thanhdatpb.listingguard.rag.analysis;

/** Retrieval components of the ADR-011 descriptor. Prompt and LLM identity are supplied by the analysis pipeline. */
public record AnalyzerDescriptor(String profileVersion, String profileHash, String corpusFingerprint, String chunkerVersion,
                                 String embeddingModel, String pipelineVersion, String algorithmVersion) {
}

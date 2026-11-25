package com.thanhdatpb.listingguard.client.model;

import java.util.Map;

/**
 * What the LLM judge was given with the sentence when it lowered a trademark finding, and why it did (schema
 * {@code TrademarkJudgeEvidence}). The evidence is computed by the server, not by the model; a field it did not have is
 * {@code unknown}. It holds no prompt, no sentence of the listing and no mark.
 *
 * @param promptVersion the version of the judge prompt file that was used
 * @param evidence      the evidence fields as they were sent, by name
 * @param reason        the model's reason after the schema check, or {@code null}
 */
public record TrademarkJudgeEvidence(String promptVersion, Map<String, String> evidence, String reason) {
}

package com.thanhdatpb.listingguard.trademark;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a language model was given and answered when it lowered a finding (ADR-051): the prompt version, the evidence pack the
 * program computed and put in front of it, and the reason it gave once the answer had passed the schema. It is recorded with the
 * {@code llm_descriptive} adjustment so a reader can see what the model could see. It holds no prompt, no sentence of the listing and
 * no answer other than the checked reason.
 *
 * @param promptVersion the {@code version} of the judge prompt file that produced the answer
 * @param evidence      the evidence fields as they were sent, in the order they were sent; a field the program did not have is
 *                      {@code unknown}
 * @param reason        the model's reason, after the schema check; null until a verdict has been applied
 */
public record JudgeBasis(String promptVersion, Map<String, String> evidence, String reason) {

    public JudgeBasis {
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }

    /** The same basis with the checked reason of the model. */
    public JudgeBasis withReason(String checkedReason) {
        return new JudgeBasis(promptVersion, evidence, checkedReason);
    }
}

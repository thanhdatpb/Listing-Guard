package com.thanhdatpb.listingguard.personalization.rules;

import com.thanhdatpb.listingguard.personalization.PersonalizationInput;
import com.thanhdatpb.listingguard.personalization.Texts;
import com.thanhdatpb.listingguard.personalization.config.PersonalizationRuleset;
import java.util.List;

/**
 * What the rules of one screening share: the ruleset, the print profile in use and the cleaned list of values (ADR-016).
 * {@code values[i]} is the i-th value after the client's input was cleaned, and the field of a finding is
 * {@code personalization[i]}.
 */
public record RuleContext(PersonalizationRuleset ruleset, PersonalizationRuleset.PrintProfile profile,
                          List<PersonalizationInput.Value> values) {

    /** The longest label kept in an answer, in code points. */
    public static final int MAX_LABEL_CHARS = 64;

    public RuleContext {
        values = List.copyOf(values);
    }

    public String field(int index) {
        return "personalization[" + index + "]";
    }

    /** The label of a value, printable and cut to {@link #MAX_LABEL_CHARS}, or null when it has none. */
    public String label(int index) {
        String label = Texts.nfcStrip(values.get(index).label());
        return label.isEmpty() ? null : Texts.printable(Texts.cut(label, MAX_LABEL_CHARS));
    }

    /** How a message names a value: {@code personalization[0] (name)} or just the field when there is no label. */
    public String subject(int index) {
        String label = label(index);
        return label == null ? field(index) : field(index) + " (" + label + ")";
    }
}

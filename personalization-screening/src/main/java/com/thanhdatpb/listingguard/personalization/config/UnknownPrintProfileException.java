package com.thanhdatpb.listingguard.personalization.config;

import java.util.List;

/** A request named a print profile that the ruleset does not declare. The API answers it as a 400 (ADR-016). */
public class UnknownPrintProfileException extends IllegalArgumentException {

    private final String code;
    private final List<String> known;

    public UnknownPrintProfileException(String code, List<String> known) {
        super("Unknown print profile '" + code + "'; declared profiles: " + known);
        this.code = code;
        this.known = List.copyOf(known);
    }

    public String code() {
        return code;
    }

    /** The profile codes the ruleset declares, default first. */
    public List<String> known() {
        return known;
    }
}

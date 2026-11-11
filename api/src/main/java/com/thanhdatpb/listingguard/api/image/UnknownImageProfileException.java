package com.thanhdatpb.listingguard.api.image;

import java.util.List;

/**
 * The request named an image profile that the marketplace's ruleset does not declare. It is never replaced by the default,
 * because the answer would then describe a profile the client did not name.
 */
public class UnknownImageProfileException extends RuntimeException {

    private final List<String> known;

    public UnknownImageProfileException(String profile, List<String> known) {
        super("Unknown image profile '" + profile + "'. Declared profiles: " + known + ".");
        this.known = List.copyOf(known);
    }

    public List<String> known() {
        return known;
    }
}

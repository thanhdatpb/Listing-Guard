package com.thanhdatpb.listingguard.ruleengine.hygiene;

import java.util.List;

/** Reads the facts of one kind from a text, in the order they appear. A mention the surrounding words make ambiguous is not returned. */
public interface FactExtractor {

    List<Mention> extract(String text);
}

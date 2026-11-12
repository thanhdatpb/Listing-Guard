package com.thanhdatpb.listingguard.client.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unmodifiable copies for the records: a missing list or map is empty, never {@code null} (a tolerant reader, ADR-023). */
final class Copies {

    private Copies() {
    }

    static <T> List<T> list(List<T> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    static <K, V> Map<K, V> map(Map<K, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}

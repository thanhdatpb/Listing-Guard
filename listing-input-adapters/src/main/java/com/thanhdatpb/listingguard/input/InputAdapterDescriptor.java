package com.thanhdatpb.listingguard.input;

import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Public, non-secret metadata for an installed input adapter. */
public record InputAdapterDescriptor(String id, String marketplace, Set<String> formats,
                                     List<String> requiredColumns, boolean experimental, String family) {
    public InputAdapterDescriptor {
        formats = Collections.unmodifiableSet(new TreeSet<>(formats));
        requiredColumns = List.copyOf(requiredColumns);
    }
}

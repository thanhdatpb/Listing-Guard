package com.thanhdatpb.listingguard.input;

import java.util.List;
import java.util.Optional;

/** Immutable catalog shared by batch jobs, the console, and the read-only API. */
public final class AdapterCatalog {
    private final List<ListingInputAdapter> adapters;

    public AdapterCatalog(List<ListingInputAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    public List<ListingInputAdapter> all() { return adapters; }

    public Optional<ListingInputAdapter> find(String id) {
        return adapters.stream().filter(adapter -> adapter.id().equals(id)).findFirst();
    }
}

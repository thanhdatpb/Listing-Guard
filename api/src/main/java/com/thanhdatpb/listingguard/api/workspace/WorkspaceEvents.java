package com.thanhdatpb.listingguard.api.workspace;

import java.util.List;
import java.util.UUID;

/**
 * What the workspace says happened, for whoever listens (ADR-061). Nothing in the workspace waits for an answer; with the background audit
 * off, nobody listens and the events change nothing. They carry ids, never a listing's text.
 */
public final class WorkspaceEvents {

    private WorkspaceEvents() {
    }

    /** An import wrote these listings (new or changed); it is published inside the import's transaction and heard after it commits. */
    public record ListingsImported(UUID clientId, UUID accountId, List<UUID> listingIds, UUID actorId) {

        public ListingsImported {
            listingIds = List.copyOf(listingIds);
        }
    }

    /** One listing was saved by a person (the editor of a later change). It is audited at once, in the same request. */
    public record ListingSaved(UUID clientId, UUID listingId) {
    }
}

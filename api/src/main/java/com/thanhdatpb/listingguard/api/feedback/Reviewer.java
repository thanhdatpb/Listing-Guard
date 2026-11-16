package com.thanhdatpb.listingguard.api.feedback;

import java.util.Objects;
import java.util.UUID;

/**
 * Who gives a verdict (ADR-034): a console account, or the API key a request came with. One verdict per reviewer per finding.
 *
 * @param kind the kind of reviewer
 * @param id   the account's id, or the {@code api_key.id} row id (not the public key id, never the secret)
 */
public record Reviewer(Kind kind, UUID id) {

    public enum Kind {
        ACCOUNT, API_KEY
    }

    public Reviewer {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    public static Reviewer account(UUID accountId) {
        return new Reviewer(Kind.ACCOUNT, accountId);
    }

    public static Reviewer apiKey(UUID apiKeyRowId) {
        return new Reviewer(Kind.API_KEY, apiKeyRowId);
    }
}

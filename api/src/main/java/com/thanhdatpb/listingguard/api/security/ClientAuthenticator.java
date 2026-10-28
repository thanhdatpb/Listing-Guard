package com.thanhdatpb.listingguard.api.security;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import java.util.Optional;

/** Turns the key of an {@code X-API-Key} header into the client that owns it. */
public interface ClientAuthenticator {

    /** @return the client when the key is known and both the key and its client are active, otherwise empty */
    Optional<AuthenticatedClient> authenticate(String presentedKey);
}

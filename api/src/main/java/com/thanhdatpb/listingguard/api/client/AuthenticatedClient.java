package com.thanhdatpb.listingguard.api.client;

import java.util.UUID;

/** Who is calling: the client and the key it used. Set on the request once the API key is accepted. */
public record AuthenticatedClient(UUID id, String name, String keyId) {
}

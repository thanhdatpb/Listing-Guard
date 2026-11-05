package com.thanhdatpb.listingguard.api.console;

import java.io.Serializable;
import java.util.UUID;

public record ConsoleUser(String email, UUID clientId) implements Serializable {
}

package com.thanhdatpb.listingguard.api.review;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/** A position in a tenant-scoped, filtered list. Neither the scope nor the position grants access. */
record ReviewCursor(OffsetDateTime createdAt, UUID id) {
    String encode(String scope) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((scope + "|" + createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    static ReviewCursor decode(String value, String scope) {
        if (value.length() > 384) {
            throw ReviewCaseService.invalidQuery();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String prefix = scope + "|";
            if (decoded.startsWith(prefix)) {
                String[] parts = decoded.substring(prefix.length()).split("\\|", -1);
                if (parts.length == 2) {
                    OffsetDateTime createdAt = OffsetDateTime.parse(parts[0]);
                    if (createdAt.getYear() >= 1 && createdAt.getYear() <= 9999) {
                        return new ReviewCursor(createdAt, UUID.fromString(parts[1]));
                    }
                }
            }
        } catch (IllegalArgumentException | DateTimeException e) {
            throw ReviewCaseService.invalidQuery();
        }
        throw ReviewCaseService.invalidQuery();
    }
}

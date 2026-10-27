package com.thanhdatpb.listingguard.api.check;

import java.util.List;
import java.util.UUID;

public record CheckResponse(
        UUID checkId,
        String marketplace,
        CheckStatus status,
        String contentHash,
        List<Finding> findings) {
}

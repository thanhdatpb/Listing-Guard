package com.thanhdatpb.listingguard.api.check;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CheckRequest(@NotBlank String marketplace, @NotNull JsonNode listing) {
}

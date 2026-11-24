package com.thanhdatpb.listingguard.api.policy;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PolicyCheckRequest(@NotBlank @Schema(example = "ETSY") String marketplace,
                                 @NotNull @Schema(types = "object") JsonNode listing,
                                 @Pattern(regexp = "en|vi") @Schema(defaultValue = "en") String language) { }

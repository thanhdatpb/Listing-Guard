package com.thanhdatpb.listingguard.api.phrase;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PhraseScreeningRequest(
        @NotNull @Size(min = 1, max = PhraseScreeningProperties.MAX_PHRASES,
                message = "Send between 1 and 2000 phrases per request.")
        List<@NotNull @Valid PhraseInput> phrases,
        @Size(max = 32) @Schema(defaultValue = "ETSY", description = "Case-insensitive marketplace code.") String marketplace,
        @JsonProperty("product_type") @Size(max = 64) String productType) {

    public record PhraseInput(
            @Schema(description = "Phrase text. Empty values and values over 200 Unicode code points are counted and skipped.")
            String text,
            @PositiveOrZero Long searchVolume) {}
}

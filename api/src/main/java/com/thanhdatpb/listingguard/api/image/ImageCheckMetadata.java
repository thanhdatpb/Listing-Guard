package com.thanhdatpb.listingguard.api.image;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thanhdatpb.listingguard.image.ImageRole;
import com.thanhdatpb.listingguard.image.ImageSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code metadata} part of {@code POST /api/v1/image-checks} (docs/input-schema.md, "Image checks"): a JSON object with the
 * marketplace, an optional profile and one entry per image part, in the same order as the {@code images} parts. Read strictly:
 * an unknown member, a missing role or a value that is not one of the declared names is a 400, never a guess (ADR-008).
 *
 * @param marketplace marketplace code, case-insensitive
 * @param profile     the image profile of the marketplace's ruleset; absent means the deployment's default profile
 * @param images      one entry per {@code images} part
 */
public record ImageCheckMetadata(
        @Schema(description = "Marketplace code, case-insensitive. Supported: ETSY, AMAZON.", example = "AMAZON")
        String marketplace,
        @Schema(description = "A profile declared in the marketplace's image ruleset. Absent means the default profile. An "
                + "undeclared name is a 400 (`unknown-image-profile`), never replaced by the default.",
                nullable = true, example = "default")
        String profile,
        @Schema(description = "One entry per `images` file part, in the same order: the declared role and source of that image.")
        List<ImageMeta> images) {

    /**
     * What the client declares about one image.
     *
     * @param role   {@code MAIN} or {@code ADDITIONAL}; required
     * @param source {@code SELLER}, {@code CUSTOMER_CONTENT} or {@code UNKNOWN}; absent means {@code UNKNOWN}. The checker cannot
     *               verify it (ADR-008)
     */
    public record ImageMeta(
            @Schema(description = "MAIN or ADDITIONAL. Required.", allowableValues = {"MAIN", "ADDITIONAL"}) String role,
            @JsonProperty("source")
            @Schema(description = "Where the image came from, as the client declares it: SELLER, CUSTOMER_CONTENT or UNKNOWN. "
                    + "Absent means UNKNOWN. A CUSTOMER_CONTENT image gets a warning to check it by hand for third-party "
                    + "logos or marks; the checker does not look at pixels for that.",
                    allowableValues = {"SELLER", "CUSTOMER_CONTENT", "UNKNOWN"}, nullable = true)
            String source) {

        ImageRole parsedRole(int index) {
            if (role == null) {
                throw new IllegalArgumentException("images[" + index + "].role is required (MAIN or ADDITIONAL).");
            }
            try {
                return ImageRole.valueOf(role);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("images[" + index + "].role must be MAIN or ADDITIONAL.");
            }
        }

        ImageSource parsedSource(int index) {
            if (source == null) {
                return ImageSource.UNKNOWN;
            }
            try {
                return ImageSource.valueOf(source);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "images[" + index + "].source must be SELLER, CUSTOMER_CONTENT or UNKNOWN.");
            }
        }
    }

    /**
     * @throws IllegalArgumentException if the text is not a JSON object of this shape (a 400 {@code invalid-request})
     */
    static ImageCheckMetadata parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("The metadata part is empty.");
        }
        ImageCheckMetadata metadata;
        try {
            metadata = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json, ImageCheckMetadata.class);
        } catch (JsonProcessingException e) {
            // the message of a parser failure may quote the text; say only where the problem is not what it says
            throw new IllegalArgumentException("The metadata part is not a valid JSON object of the documented shape.");
        }
        if (metadata == null || metadata.marketplace() == null || metadata.marketplace().isBlank()) {
            throw new IllegalArgumentException("metadata.marketplace is required.");
        }
        if (metadata.images() == null || metadata.images().isEmpty()) {
            throw new IllegalArgumentException("metadata.images must list one entry per image part.");
        }
        for (int i = 0; i < metadata.images().size(); i++) {
            if (metadata.images().get(i) == null) {
                throw new IllegalArgumentException("images[" + i + "] must be an object with a role.");
            }
        }
        return metadata;
    }

    /** The entries as the checker's types; the first invalid one stops with a message that names it. */
    List<Parsed> parsedImages() {
        List<Parsed> parsed = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            parsed.add(new Parsed(images.get(i).parsedRole(i), images.get(i).parsedSource(i)));
        }
        return parsed;
    }

    record Parsed(ImageRole role, ImageSource source) {
    }
}

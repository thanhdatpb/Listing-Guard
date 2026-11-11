package com.thanhdatpb.listingguard.api.image;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.thanhdatpb.listingguard.core.finding.Severity;
import com.thanhdatpb.listingguard.image.ImageItemResult.Completeness;
import com.thanhdatpb.listingguard.image.ImageRole;
import com.thanhdatpb.listingguard.image.ImageSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of an image check: the response of {@code POST /api/v1/image-checks} and {@code GET /api/v1/image-checks/{id}}
 * (ADR-008). A reference warning built on a marketplace image profile; the {@code disclaimer} says so. A pack-specific DTO that
 * shares nothing with the other responses. It carries no image content and no file name: the client has its images, and the
 * {@code ordinal} (the position in the request) and the {@code imageHash} say which one a finding belongs to.
 */
@JsonPropertyOrder({"checkId", "marketplace", "profile", "rulesetVersion", "analysisVersion", "cached", "completeness", "summary",
        "failedRules", "disclaimer", "createdAt", "expiresAt", "findings", "images"})
public record ImageCheckResult(
        @Schema(description = "Identifier of the stored check; use it with GET /api/v1/image-checks/{id} until expiresAt.")
        UUID checkId,
        @Schema(description = "Marketplace code, upper case.", example = "AMAZON")
        String marketplace,
        @Schema(description = "The image profile that was used; the default when the request named none.", example = "default")
        String profile,
        @Schema(description = "Version of the image ruleset that produced the result.", example = "amazon-image-2026-11")
        String rulesetVersion,
        @Schema(description = "Version of everything that decides the result (ruleset, algorithm versions, the image runtime). "
                + "Opaque: compare for equality only. Two checks of one client with the same marketplace, profile, images and "
                + "metadata and the same analysisVersion have the same result.",
                example = "9a4e1c7d03b85f26e1d0c9b8a7f6e5d4c3b2a19087766554433221100ffeeddc")
        String analysisVersion,
        @Schema(description = "True when the stored result of an identical earlier check is returned instead of a new analysis. "
                + "Only a COMPLETE result is ever returned from the cache.")
        boolean cached,
        @Schema(description = "COMPLETE when every check ran on every image. INCOMPLETE when one did not (failedRules names it; an "
                + "image that cannot be decoded, or a threshold the ruleset does not have yet, is such a case): the findings that "
                + "were established are kept. An INCOMPLETE result is never a clean result, is never reused and costs no quota.")
        Completeness completeness,
        @Schema(description = "The outcome the ruleset maps from the highest severity found (for example READY, REVIEW, BLOCK); "
                + "always INCOMPLETE when completeness is INCOMPLETE. A summary, not a verdict of the marketplace.",
                example = "REVIEW")
        String summary,
        @Schema(description = "Checks that did not run: a pack-level rule id such as image_count, or images[i].rule for one image. "
                + "Empty when none failed.", example = "[]")
        List<String> failedRules,
        @Schema(description = "Statement that the result is a reference warning and not a statement of the marketplace. Show it with "
                + "the result.")
        String disclaimer,
        @Schema(description = "When the check was first stored (UTC).")
        Instant createdAt,
        @Schema(description = "When the stored result stops being readable (UTC). After this instant GET answers 404 and the same "
                + "images are analysed again.")
        Instant expiresAt,
        @Schema(description = "Findings about the set of images as a whole, such as the image count.")
        List<FindingView> findings,
        @Schema(description = "One entry per submitted image, in the order of the request.")
        List<ImageView> images) {

    /** One thing a rule found. {@code evidence} is a measured value such as {@code count=3}, never image content. */
    @JsonPropertyOrder({"ruleId", "severity", "sourceStatus", "message", "evidence"})
    public record FindingView(
            @Schema(description = "Stable rule id.", example = "image_count") String ruleId,
            @Schema(description = "ERROR, WARNING or INFO. Every shipped image rule is unverified, so none is ERROR.")
            Severity severity,
            @Schema(description = "Status of the source behind the rule: verified, secondary or unverified.", example = "unverified")
            String sourceStatus,
            @Schema(description = "What was found and what to check, in Vietnamese.") String message,
            @Schema(description = "The measured value, at most 200 characters.", example = "count=3") String evidence) {
    }

    /** What the decoder read from an image (after the EXIF orientation of the file was applied). */
    public record Metadata(
            @Schema(description = "JPEG or PNG, read from the bytes, never from the file name or the content type.", example = "JPEG")
            String format,
            @Schema(description = "Width in pixels after the orientation was applied.") int width,
            @Schema(description = "Height in pixels after the orientation was applied.") int height,
            @Schema(description = "EXIF orientation of the file, 1 when it has none.") int orientation,
            @Schema(description = "The image has an alpha channel; it was composited onto white for the checks.") boolean hadAlpha,
            @Schema(description = "The image has an embedded ICC profile; it was converted to sRGB for the checks.") boolean hadIccProfile) {
    }

    /** The outcome for one submitted image. */
    @JsonPropertyOrder({"ordinal", "imageHash", "role", "source", "completeness", "failedRules", "metadata", "findings"})
    public record ImageView(
            @Schema(description = "Zero-based position of the image in the request.") int ordinal,
            @Schema(description = "SHA-256 of the submitted bytes, lower-case hex.") String imageHash,
            @Schema(description = "The role the client declared.") ImageRole role,
            @Schema(description = "Where the client declared the image came from; UNKNOWN when it declared none.") ImageSource source,
            @Schema(description = "INCOMPLETE when a check could not run on this image, for example it could not be decoded.")
            Completeness completeness,
            @Schema(description = "Checks that did not run on this image.", example = "[]") List<String> failedRules,
            @Schema(description = "Absent when the image could not be decoded.") @JsonInclude(JsonInclude.Include.NON_NULL)
            Metadata metadata,
            @Schema(description = "Findings about this image.") List<FindingView> findings) {
    }
}

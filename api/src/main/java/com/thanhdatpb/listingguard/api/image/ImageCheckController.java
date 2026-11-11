package com.thanhdatpb.listingguard.api.image;

import static com.thanhdatpb.listingguard.api.docs.ApiExamples.IMAGE_METADATA;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.IMAGE_RESULT;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_IMAGE_CHECKS_NOT_CONFIGURED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_IMAGE_CHECK_NOT_FOUND;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_INVALID_REQUEST;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_PLAN_NOT_ALLOWED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_QUOTA_EXCEEDED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_RATE_LIMITED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_TOO_LARGE;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_TOO_MANY_IMAGES;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNAUTHORIZED;
import static com.thanhdatpb.listingguard.api.docs.ApiExamples.PROBLEM_UNKNOWN_IMAGE_PROFILE;

import com.thanhdatpb.listingguard.api.client.AuthenticatedClient;
import com.thanhdatpb.listingguard.api.security.ApiKeyFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The image-checks pack's own endpoint (ADR-008, ADR-010). {@code /api/v1/checks} and the other packs' endpoints neither show
 * nor depend on it. It takes {@code multipart/form-data} only: no image is ever fetched from a URL or read from a path.
 */
@RestController
@RequestMapping("/api/v1/image-checks")
@Tag(name = "Image checks",
        description = "The images of a listing: format, size, count, background, occupancy and sharpness against a marketplace profile.")
public class ImageCheckController {

    private static final String PROBLEM = "application/problem+json";

    private final ImageCheckService service;

    public ImageCheckController(ImageCheckService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            operationId = "checkImages",
            summary = "Check the images of a listing",
            description = """
                    Takes the images of one listing as `multipart/form-data` and checks each against the image profile of the \
                    marketplace (see docs/input-schema.md, "Image checks"). Two kinds of part: `metadata`, a JSON object with the \
                    marketplace, an optional profile and one `{role, source}` entry per image, and one `images` file part per \
                    image, in the same order. Images are never fetched from a URL or read from a path. The format is read from \
                    the bytes (JPEG and PNG), never from the file name or the content type. An image that cannot be read \
                    (corrupt, unsupported, CMYK, too many pixels) is not an error of the request: it is answered as an INCOMPLETE \
                    image and the others are still checked. A threshold the marketplace ruleset does not have yet also makes a \
                    check INCOMPLETE and names it in `failedRules`; the shipped rulesets have none until the maintainer has \
                    reviewed the official pages, so every check of such a ruleset is INCOMPLETE and no verdict is given. \
                    `source` is only the caller's declaration; a CUSTOMER_CONTENT image gets a warning to check it by hand for \
                    third-party logos or marks (nothing here recognizes a logo, and no finding does not mean an image is free of \
                    one). The result is a reference warning, not a statement of the marketplace; show the `disclaimer` with it. \
                    If the same client already sent the same images and metadata with the same analysisVersion and the stored \
                    result is complete and still retained, it is returned with `cached: true`. An INCOMPLETE result is stored so \
                    its id can be read again, and is never reused for another request. A new complete result counts one unit of \
                    the client's monthly quota where plans are on (see `GET /api/v1/usage`); a cached or an INCOMPLETE one costs \
                    nothing. Results are kept for a limited time (`expiresAt`); the images and their file names are never \
                    stored, only their hashes, what was read from them and the findings. Off by default: a deployment that has \
                    not enabled it answers 503 `image-checks-not-configured`.""",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The check result.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ImageCheckResult.class),
                                    examples = @ExampleObject(name = "incomplete", summary = "An incomplete check with an undecodable image",
                                            value = IMAGE_RESULT))),
                    @ApiResponse(responseCode = "400", description = "Invalid request (code `invalid-request`: a missing part, metadata that does not fit the shape, a role or source that is not one of the declared names, a number of entries that does not match the number of images), unknown marketplace (`unknown-marketplace`), too many images (`too-many-images`) or a profile the ruleset does not declare (`unknown-image-profile`, with the declared names in `known`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = {
                                            @ExampleObject(name = "invalid-request", value = PROBLEM_INVALID_REQUEST),
                                            @ExampleObject(name = "too-many-images", value = PROBLEM_TOO_MANY_IMAGES),
                                            @ExampleObject(name = "unknown-image-profile", value = PROBLEM_UNKNOWN_IMAGE_PROFILE)})),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "403", description = "The client's plan does not include this pack (code `plan-not-allowed`; only where plans are on). Retrying does not help.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_PLAN_NOT_ALLOWED))),
                    @ApiResponse(responseCode = "413", description = "One image or the whole request is over the size limit of the deployment (code `payload-too-large`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_TOO_LARGE))),
                    @ApiResponse(responseCode = "415", description = "The body is not multipart/form-data (code `unsupported-media-type`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "The marketplace is planned but not supported yet (code `marketplace-not-yet-supported`).",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "429", description = "Two different answers, told apart by `code`. `rate-limited`: too many requests for the calling client (the same limit as the other endpoints); retry after the Retry-After header. `quota-exceeded` (only where plans are on): the month's quota of the client's plan is used up; there is no Retry-After, and retrying before `resetAt` does not help. A result already stored is still answered.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = {
                                            @ExampleObject(name = "rate-limited", value = PROBLEM_RATE_LIMITED),
                                            @ExampleObject(name = "quota-exceeded", value = PROBLEM_QUOTA_EXCEEDED)})),
                    @ApiResponse(responseCode = "503", description = "Image checks are not enabled in this deployment (code `image-checks-not-configured`). Only this endpoint is affected.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_IMAGE_CHECKS_NOT_CONFIGURED)))})
    public ImageCheckResult check(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Parameter(description = "JSON object: marketplace, optional profile, and images[] with one {role, source} per image part, "
                    + "in the same order as the `images` parts. Send it as a plain form field or as application/json.",
                    example = IMAGE_METADATA)
            @RequestPart("metadata") String metadata,
            @Parameter(description = "The image files, one part named `images` per image (JPEG or PNG), in the order of metadata.images.")
            @RequestPart("images") List<MultipartFile> images) {
        return service.check(client.id(), metadata, images);
    }

    @GetMapping("/{id}")
    @Operation(
            operationId = "getImageCheck",
            summary = "Read a stored image check",
            description = "Returns the stored result of an earlier image check of the calling client while it is retained, in the "
                    + "same shape as the POST response (`cached` is false). After `expiresAt`, for an unknown id and for another "
                    + "client's check the answer is the same 404.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The stored result.",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ImageCheckResult.class),
                                    examples = @ExampleObject(value = IMAGE_RESULT))),
                    @ApiResponse(responseCode = "400", description = "The id is not a UUID.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "401", description = "Missing or invalid API key.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_UNAUTHORIZED))),
                    @ApiResponse(responseCode = "404", description = "No retained image check with this id for the calling client (code `image-check-not-found`). A check of another client, an id that never existed and an expired check give the same answer.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_IMAGE_CHECK_NOT_FOUND))),
                    @ApiResponse(responseCode = "429", description = "Rate limit exceeded for the calling client.",
                            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemDetail.class),
                                    examples = @ExampleObject(value = PROBLEM_RATE_LIMITED)))})
    public ImageCheckResult get(
            @Parameter(hidden = true) @RequestAttribute(ApiKeyFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
            @Parameter(description = "checkId returned by POST /api/v1/image-checks") @PathVariable UUID id) {
        return service.get(client.id(), id);
    }
}

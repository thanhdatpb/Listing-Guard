package com.thanhdatpb.listingguard.api.docs;

import com.thanhdatpb.listingguard.api.web.ApiContractHeadersFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String API_KEY_SCHEME = "ApiKeyAuth";

    /** Spring's own problem schema, and the members of it that the published contract declares nullable. */
    static final String PROBLEM_DETAIL = "ProblemDetail";

    static final List<String> PROBLEM_DETAIL_NULLABLE_MEMBERS = List.of("title", "detail", "instance", "properties");

    @Bean
    public OpenAPI listingGuardOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Listing Guard API")
                        .version("v1")
                        .description("""
                                Checks a marketplace listing before it is published and returns findings with suggestions. \
                                It only checks and suggests: it never edits a listing and never publishes anything.

                                Every endpoint except GET /health needs the X-API-Key header. Errors are RFC 7807 problem \
                                responses (application/problem+json) with a stable `code`; quote the `requestId` when reporting one. \
                                See docs/integration-guide.md for the calling flow, error table and field path conventions.

                                Every /api/v1 response carries X-Listing-Guard-Version, the release of the server. Inside /api/v1 \
                                changes are additive only (ADR-022): ignore unknown fields and accept unknown enum values. The \
                                contract of each release is attached to its GitHub Release (openapi.json, input-schema.json, \
                                contract-sha256.txt)."""))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")
                        .description("Service-to-service API key issued to a client. A client can hold several keys at once to allow rotation; "
                                + "checks, cached results and the rate limit belong to the client, not to the key.")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }

    /**
     * Documents the contract headers of ADR-022 once under {@code components.headers} and references them from every response
     * of every {@code /api/v1} operation: {@code X-Listing-Guard-Version} (always there) and {@code Deprecation} and
     * {@code Sunset} (only for an operation the deployment deprecates). {@code GET /health} gets none.
     */
    @Bean
    public OpenApiCustomizer apiContractHeaders() {
        return openApi -> {
            openApi.getComponents()
                    .addHeaders(ApiContractHeadersFilter.VERSION_HEADER, new Header()
                            .description("The release version of the server that answered, for example `0.8.0`; `dev` for a build "
                                    + "without a release version. On every response under /api/v1 (ADR-022). Use it to fetch the "
                                    + "contract of that release; it is not for feature detection (see GET /api/v1/capabilities).")
                            .required(true)
                            .schema(new StringSchema().example("0.8.0")))
                    .addHeaders(ApiContractHeadersFilter.DEPRECATION_HEADER, new Header()
                            .description("Only when this deployment deprecates the operation (ADR-022): `@` and the time of the "
                                    + "deprecation in seconds since 1970-01-01T00:00:00Z (RFC 9745). A `Link` header with "
                                    + "`rel=\"deprecation\"` may point at the migration notes.")
                            .schema(new StringSchema().example("@1798761600")))
                    .addHeaders(ApiContractHeadersFilter.SUNSET_HEADER, new Header()
                            .description("Only when a deprecated operation has a removal date: the time after which it may stop "
                                    + "working, as an HTTP date (RFC 8594). At least 180 days after the Deprecation date (ADR-022).")
                            .schema(new StringSchema().example("Thu, 01 Jul 2027 00:00:00 GMT")));
            openApi.getPaths().forEach((path, item) -> {
                if (!path.startsWith("/api/v1/")) {
                    return;
                }
                item.readOperations().forEach(operation -> operation.getResponses().values().forEach(response -> {
                    for (String header : List.of(ApiContractHeadersFilter.VERSION_HEADER, ApiContractHeadersFilter.DEPRECATION_HEADER,
                            ApiContractHeadersFilter.SUNSET_HEADER)) {
                        response.addHeaderObject(header, new Header().$ref("#/components/headers/" + header));
                    }
                }));
            });
        };
    }

    @Bean
    public OpenApiCustomizer nullableCheckObjects() {
        return openApi -> {
            makeReferenceNullable(openApi, "CheckResult", "readiness");
            makeReferenceNullable(openApi, "FindingView", "fix");
        };
    }

    /**
     * Marks four members of Spring's own {@code ProblemDetail} schema nullable again: {@code title}, {@code detail},
     * {@code instance} and {@code properties}. The published contract ({@code docs/api/openapi.json}) declares them
     * {@code ["string", "null"]} (and {@code ["object", "null"]} for {@code properties}).
     *
     * <p>Why it is needed: Spring Framework 7 declares these members with the JSpecify {@code @Nullable}, a type-use
     * annotation, and springdoc-openapi 3.2.0 does not read it, so the generated document says {@code "string"} and
     * {@code "object"}. Nothing about a response changes; only the document is kept as it was (ADR-020).
     *
     * <p>When to remove it: when springdoc reads the JSpecify {@code @Nullable}. Delete this bean and run the build:
     * {@code OpenApiIntegrationTest} must still pass without it. The customizer is idempotent, so a springdoc that already
     * marks the members nullable is not a problem in the meantime.
     */
    @Bean
    public OpenApiCustomizer nullableProblemDetailMembers() {
        return openApi -> {
            Schema<?> problem = openApi.getComponents().getSchemas().get(PROBLEM_DETAIL);
            if (problem == null) {
                throw new IllegalStateException("Expected the components to hold the " + PROBLEM_DETAIL + " schema");
            }
            for (String member : PROBLEM_DETAIL_NULLABLE_MEMBERS) {
                Schema<?> property = problem.getProperties() == null ? null : problem.getProperties().get(member);
                if (property == null) {
                    throw new IllegalStateException("Expected " + PROBLEM_DETAIL + "." + member + " to be a property");
                }
                allowNull(property);
            }
        };
    }

    /** Adds {@code "null"} to the types of a schema, after the type it already has, whichever way that type is stored. */
    private static void allowNull(Schema<?> property) {
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        types.add("null");
        property.setTypes(types);
    }

    private static void makeReferenceNullable(OpenAPI openApi, String schemaName, String propertyName) {
        Schema<?> parent = openApi.getComponents().getSchemas().get(schemaName);
        Schema<?> property = parent.getProperties().get(propertyName);
        if (property.get$ref() == null) {
            throw new IllegalStateException("Expected " + schemaName + "." + propertyName + " to reference a schema");
        }

        Schema<?> nullable = new Schema<>();
        nullable.setDescription(property.getDescription());
        nullable.addAnyOfItem(new Schema<>().$ref(property.get$ref()));
        Schema<?> nullSchema = new Schema<>();
        nullSchema.addType("null");
        nullable.addAnyOfItem(nullSchema);
        parent.addProperty(propertyName, nullable);
    }
}

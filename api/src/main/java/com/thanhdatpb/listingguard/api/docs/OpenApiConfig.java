package com.thanhdatpb.listingguard.api.docs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
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
                                See docs/integration-guide.md for the calling flow, error table and field path conventions."""))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")
                        .description("Service-to-service API key issued to a client. A client can hold several keys at once to allow rotation; "
                                + "checks, cached results and the rate limit belong to the client, not to the key.")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
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

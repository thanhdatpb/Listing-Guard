package com.thanhdatpb.listingguard.api.docs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String API_KEY_SCHEME = "ApiKeyAuth";

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
}

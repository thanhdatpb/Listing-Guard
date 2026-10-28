package com.thanhdatpb.listingguard.api.security;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Refuses to start with settings that are only meant for development. Created as a bean, so a violation stops the
 * application before it serves a request:
 * <ul>
 *   <li>a public development key (one starting with {@value ApiKeys#INSECURE_PREFIX}, such as the compose default)
 *       needs the {@code dev} profile, and cannot be combined with {@code prod};</li>
 *   <li>the Swagger UI flag opens the UI and the OpenAPI document without a key: it logs a warning, and is refused
 *       under the {@code prod} profile.</li>
 * </ul>
 * Messages never contain a key.
 */
public final class StartupSafetyCheck {

    public static final String DEV_PROFILE = "dev";
    public static final String PROD_PROFILE = "prod";

    private static final Logger log = LoggerFactory.getLogger(StartupSafetyCheck.class);

    public StartupSafetyCheck(ApiKeys keys, boolean swaggerUiEnabled, Environment environment) {
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean prod = profiles.contains(PROD_PROFILE);

        if (keys.hasInsecureDevKey() && (!profiles.contains(DEV_PROFILE) || prod)) {
            throw new IllegalStateException("A public development API key (one starting with '" + ApiKeys.INSECURE_PREFIX
                    + "') is configured, which is only allowed with the '" + DEV_PROFILE + "' profile and never with '"
                    + PROD_PROFILE + "'. Active profiles: " + profiles + ". Set LISTING_GUARD_API_KEYS to your own keys.");
        }
        if (swaggerUiEnabled) {
            if (prod) {
                throw new IllegalStateException("LISTING_GUARD_SWAGGER_UI=true opens Swagger UI and the OpenAPI document "
                        + "without an API key, so it cannot be used with the '" + PROD_PROFILE + "' profile.");
            }
            log.warn("Swagger UI is enabled (LISTING_GUARD_SWAGGER_UI=true): Swagger UI and /v3/api-docs are open without "
                    + "an API key. Use this for development only.");
        }
    }
}

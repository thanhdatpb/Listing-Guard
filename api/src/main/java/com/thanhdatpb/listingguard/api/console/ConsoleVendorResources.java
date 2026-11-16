package com.thanhdatpb.listingguard.api.console;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the one third-party script of the console, htmx (ADR-034), from its WebJar inside the jar: no CDN, and no copy of it in the
 * repository. It sits under {@code /console/}, so it is behind the console's sign-in like the console's own files, and only while the
 * console is on.
 *
 * <p>{@link #HTMX_VERSION} must equal the {@code htmx.version} property of {@code api/pom.xml} (a test holds it and checks that the
 * file is on the class path).
 */
@Configuration
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleVendorResources implements WebMvcConfigurer {

    public static final String HTMX_VERSION = "2.0.4";

    /** The folder of the WebJar that holds {@code htmx.min.js}. */
    public static final String HTMX_LOCATION = "classpath:/META-INF/resources/webjars/htmx.org/" + HTMX_VERSION + "/dist/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/console/vendor/**").addResourceLocations(HTMX_LOCATION);
    }
}

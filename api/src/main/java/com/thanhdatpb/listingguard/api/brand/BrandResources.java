package com.thanhdatpb.listingguard.api.brand;

import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the visual identity of the console (ADR-040) with a long cache. {@code /brand/**} (the tokens, the self-hosted fonts, the
 * logos and the icons) is public, with no sign-in and no API key (see {@code ApiKeyFilter}), because the sign-in page needs it, and
 * so is the one style sheet {@code /console/app.css} (the console chain lets it through, {@code ApiSecurityConfig}). They are
 * files of the product, never data of a client.
 *
 * <p>The cache is one year and {@code immutable}, which is safe only because every address a page writes carries
 * {@code ?v=}{@link BrandAssets#version()}, and the fonts, which a style sheet names without it, are never replaced under the same
 * name (a new font file gets a new name). {@code X-Content-Type-Options: nosniff} comes from Spring Security's default headers and is
 * not touched here.
 */
@Configuration
public class BrandResources implements WebMvcConfigurer {

    static final CacheControl LONG_CACHE = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/brand/**")
                .addResourceLocations("classpath:/static/brand/")
                .setCacheControl(LONG_CACHE);
        // a pattern with a wildcard on purpose: a handler for a literal path finds no file name inside it
        registry.addResourceHandler("/console/app.*")
                .addResourceLocations("classpath:/static/console/")
                .setCacheControl(LONG_CACHE);
    }
}

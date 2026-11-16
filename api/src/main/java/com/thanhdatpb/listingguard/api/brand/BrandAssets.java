package com.thanhdatpb.listingguard.api.brand;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * The version of the console's visual assets (ADR-040): a short SHA-256 over the files of {@code static/brand/} and the console
 * style sheet {@code static/console/app.css}, computed once at start-up from the class path. Pages write it as {@code ?v=} on the
 * address of the style sheets, the logos and the icons, so those files can be cached for a year ({@link BrandResources}) and a
 * new build, which changes the hash, still reaches every browser at once.
 *
 * <p>Nothing here reads the network or the database, and the hash names no setting or person: it is only a fingerprint of the files.
 */
@Component
public class BrandAssets {

    static final String BRAND_PATTERN = "classpath*:static/brand/**";
    static final String APP_CSS = "classpath:static/console/app.css";
    private static final String BRAND_ROOT = "static/brand/";
    private static final int VERSION_LENGTH = 12;

    private final String version;

    public BrandAssets() {
        this.version = compute();
    }

    /** Twelve hexadecimal characters; the same on every start of the same build. */
    public String version() {
        return version;
    }

    private static String compute() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Resource[] brand = resolver.getResources(BRAND_PATTERN);
            Arrays.stream(brand)
                    .filter(BrandAssets::isFile)
                    .sorted(Comparator.comparing(BrandAssets::relativeName))
                    .forEach(resource -> add(digest, relativeName(resource), resource));
            Resource app = resolver.getResource(APP_CSS);
            if (app.exists()) {
                add(digest, "console/app.css", app);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, VERSION_LENGTH);
        } catch (IOException e) {
            throw new UncheckedIOException("The brand assets could not be listed", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isFile(Resource resource) {
        try {
            return resource.isReadable() && !resource.getURL().toString().endsWith("/");
        } catch (IOException e) {
            return false;
        }
    }

    private static String relativeName(Resource resource) {
        try {
            String url = resource.getURL().toString();
            int at = url.lastIndexOf(BRAND_ROOT);
            return at < 0 ? url : url.substring(at + BRAND_ROOT.length());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void add(MessageDigest digest, String name, Resource resource) {
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (InputStream in = resource.getInputStream()) {
            digest.update(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + name, e);
        }
        digest.update((byte) 0);
    }
}

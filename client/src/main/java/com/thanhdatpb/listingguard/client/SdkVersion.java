package com.thanhdatpb.listingguard.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The SDK's version, from {@code sdk.properties} (filled in by the build); {@code dev} when it cannot be read. */
final class SdkVersion {

    static final String VALUE = read();

    private SdkVersion() {
    }

    private static String read() {
        try (InputStream in = SdkVersion.class.getResourceAsStream("sdk.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").strip();
            return version.isEmpty() || version.startsWith("${") ? "dev" : version;
        } catch (IOException e) {
            return "dev";
        }
    }
}

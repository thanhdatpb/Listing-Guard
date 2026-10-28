package com.thanhdatpb.listingguard.api;

/** API keys used by the tests. They are made up and registered by {@link TestcontainersConfig}. */
public final class ApiTestKeys {

    public static final String HEADER = "X-API-Key";

    public static final String CLIENT_1 = "client-a";
    public static final String KEY_1 = "test-key-1-0123456789abcdef0123456789";

    public static final String CLIENT_2 = "client-b";
    public static final String KEY_2 = "test-key-2-0123456789abcdef0123456789";

    /** A second key of {@link #CLIENT_1}, as during a key rotation. */
    public static final String KEY_1_SECOND = "test-key-1b-0123456789abcdef0123456789";

    private ApiTestKeys() {
    }
}

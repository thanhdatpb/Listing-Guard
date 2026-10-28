package com.thanhdatpb.listingguard.api;

/** API keys used by the tests. They are made up and only ever configured in tests. */
public final class ApiTestKeys {

    public static final String HEADER = "X-API-Key";

    public static final String NAME_1 = "preview-test";
    public static final String KEY_1 = "test-key-1-0123456789abcdef0123456789";

    public static final String NAME_2 = "preview-test-2";
    public static final String KEY_2 = "test-key-2-0123456789abcdef0123456789";

    /** The value of the {@code listing-guard.security.api-keys} property: name:key pairs, comma separated. */
    public static final String CONFIGURED = NAME_1 + ":" + KEY_1 + "," + NAME_2 + ":" + KEY_2;

    private ApiTestKeys() {
    }
}

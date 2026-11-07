package com.thanhdatpb.listingguard.mcp;

/**
 * A start-up setting is missing or wrong. The message names the setting and says what is wrong; it never contains a
 * value (a base URL may carry credentials and the API key is a secret), so it is safe to print on standard error.
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message, null, false, false);
    }
}

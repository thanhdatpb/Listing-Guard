package com.thanhdatpb.listingguard.api.security;

import com.thanhdatpb.listingguard.api.client.ApiKeyRepository;
import com.thanhdatpb.listingguard.api.client.ClientProvisioning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Runs once when the web application has started. Registers the development key as client {@code dev} when one is
 * configured ({@link StartupSafetyCheck} has already refused it outside the {@code dev} profile), then warns when no
 * key can authenticate at all, since every request except {@code /health} would get 401.
 */
public final class ClientBootstrap implements ApplicationRunner {

    public static final String DEV_CLIENT = "dev";

    private static final Logger log = LoggerFactory.getLogger(ClientBootstrap.class);

    private final ClientProvisioning provisioning;
    private final ApiKeyRepository keys;
    private final String devApiKey;

    public ClientBootstrap(ClientProvisioning provisioning, ApiKeyRepository keys, String devApiKey) {
        this.provisioning = provisioning;
        this.keys = keys;
        this.devApiKey = devApiKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (devApiKey != null && !devApiKey.isBlank()) {
            if (provisioning.ensureKnownKey(DEV_CLIENT, devApiKey.trim(), "development key")) {
                log.info("Registered the development key for client '{}' (profile dev only).", DEV_CLIENT);
            }
        }
        if (keys.countUsable() == 0) {
            log.warn("No active API key exists, so every request except /health will get 401. Create a client and a key with "
                    + "the admin job: docker compose run --rm admin client create <name>, then key create <name>.");
        }
    }
}

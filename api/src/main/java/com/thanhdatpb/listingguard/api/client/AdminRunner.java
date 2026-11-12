package com.thanhdatpb.listingguard.api.client;

import com.thanhdatpb.listingguard.api.trademark.CatalogAdminCommands;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-shot job: {@code --spring.profiles.active=admin} runs one command (see {@link AdminCommands}), then the
 * application exits (the profile turns the web server off). A refused command makes the process exit with a
 * non-zero code.
 *
 * <p>This is a runner and not an HTTP endpoint on purpose: managing clients and keys is operator work, and an
 * endpoint for it would be attack surface on the service that clients call (ADR-007).
 */
@Component
@Profile("admin")
public class AdminRunner implements ApplicationRunner {

    private final ClientProvisioning provisioning;
    private final CatalogAdminCommands catalog;

    public AdminRunner(ClientProvisioning provisioning, CatalogAdminCommands catalog) {
        this.provisioning = provisioning;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        new AdminCommands(provisioning, catalog, System.out).execute(args);
    }
}

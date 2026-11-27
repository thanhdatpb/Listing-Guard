package com.thanhdatpb.listingguard.api.client;

import com.thanhdatpb.listingguard.api.console.ConsoleAccountImporter;
import com.thanhdatpb.listingguard.api.console.ConsoleAccountRoles;
import com.thanhdatpb.listingguard.api.trademark.CatalogAdminCommands;
import com.thanhdatpb.listingguard.api.demo.DemoWorkspaceCommands;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ConsoleAccountRoles accounts;
    private final ConsoleAccountImporter importer;

    public AdminRunner(ClientProvisioning provisioning, CatalogAdminCommands catalog, ConsoleAccountRoles accounts,
                       ConsoleAccountImporter importer) {
        this.accounts = accounts;
        this.importer = importer;
        this.provisioning = provisioning;
        this.catalog = catalog;
    }

    @Autowired(required = false)
    private DemoWorkspaceCommands demo;

    @Autowired(required = false)
    private com.thanhdatpb.listingguard.api.workspace.WorkspaceDataCommands workspace;

    @Override
    public void run(ApplicationArguments args) {
        if (args.getNonOptionArgs().stream().findFirst().filter("demo"::equals).isPresent()) {
            if (demo == null) {
                throw new IllegalArgumentException("Demo workspace is disabled.");
            }
            demo.execute(args);
            System.out.println("Demo command completed.");
            return;
        }
        if (args.getNonOptionArgs().stream().findFirst().filter("workspace"::equals).isPresent()) {
            if (workspace == null) {
                throw new IllegalArgumentException("The workspace commands are not available.");
            }
            workspace.execute(args);
            return;
        }
        new AdminCommands(provisioning, catalog, accounts, importer, System.out).execute(args);
    }
}

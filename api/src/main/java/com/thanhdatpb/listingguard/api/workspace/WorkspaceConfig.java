package com.thanhdatpb.listingguard.api.workspace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

/**
 * The listing workspace's configuration (ADR-059). The properties always bind (so the flag can be read); every other bean of the workspace
 * exists only while {@code listing-guard.workspace.enabled} is true.
 */
@Configuration
@EnableConfigurationProperties(WorkspaceProperties.class)
public class WorkspaceConfig {

    @Bean
    @ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
    public StandardTargets standardTargets() {
        return StandardTargets.load();
    }

    @Bean
    @ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
    public EtsyFields etsyFields() {
        return EtsyFields.load();
    }

    /**
     * Refuses to start when an import may be larger than the multipart limit: Spring would reject the upload before the workspace could
     * say why, and the operator would not know which setting to change.
     */
    @Bean
    @ConditionalOnProperty(prefix = "listing-guard.workspace", name = "enabled", havingValue = "true")
    public WorkspaceStartupCheck workspaceStartupCheck(WorkspaceProperties properties, Environment environment) {
        DataSize multipart = environment.getProperty("spring.servlet.multipart.max-file-size", DataSize.class, DataSize.ofMegabytes(1));
        long needed = Math.max(properties.importMaxBytes().toBytes(), properties.maxTemplateBytes().toBytes());
        if (needed > multipart.toBytes()) {
            throw new IllegalStateException("listing-guard.workspace: import-max-bytes and max-template-bytes (" + needed
                    + " bytes) exceed spring.servlet.multipart.max-file-size (" + multipart.toBytes()
                    + " bytes). Raise LISTING_GUARD_MULTIPART_MAX_FILE_SIZE and LISTING_GUARD_MULTIPART_MAX_REQUEST_SIZE or lower the workspace limits.");
        }
        return new WorkspaceStartupCheck();
    }

    /** Marker bean: its creation means the check passed. */
    public static final class WorkspaceStartupCheck {
    }
}

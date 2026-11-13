package com.thanhdatpb.listingguard.api.console;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class ConsoleAccessModeCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("listing-guard.console.auth-mode", "MAGIC_LINK");
        return mode.equalsIgnoreCase("CLOUDFLARE_ACCESS") || mode.equalsIgnoreCase("cloudflare-access")
                || mode.equalsIgnoreCase("BOTH");
    }
}

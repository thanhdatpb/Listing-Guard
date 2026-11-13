package com.thanhdatpb.listingguard.api.console;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public final class ConsoleMagicLinkModeCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String mode = context.getEnvironment().getProperty("listing-guard.console.auth-mode", "MAGIC_LINK");
        return mode.equalsIgnoreCase("MAGIC_LINK") || mode.equalsIgnoreCase("magic-link")
                || mode.equalsIgnoreCase("BOTH");
    }
}

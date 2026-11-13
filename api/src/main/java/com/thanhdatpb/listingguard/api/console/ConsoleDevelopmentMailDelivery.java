package com.thanhdatpb.listingguard.api.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
@org.springframework.context.annotation.Conditional(ConsoleMagicLinkModeCondition.class)
public class ConsoleDevelopmentMailDelivery implements MagicLinkDelivery {

    private static final Logger log = LoggerFactory.getLogger(ConsoleDevelopmentMailDelivery.class);

    @Override
    public void send(String email, String link) {
        log.info("Development sign-in link: {}", link);
    }
}

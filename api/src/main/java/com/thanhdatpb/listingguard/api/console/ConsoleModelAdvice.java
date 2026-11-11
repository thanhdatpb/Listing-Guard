package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * What every console page needs to know and no controller should have to repeat (ADR-014 B4a).
 *
 * <p>{@code demoCatalog} is true while the configured trademark catalog version starts with {@value #DEMO_PREFIX}: the
 * invented marks of {@code catalogs/demo/}, whose manifest label is {@code demo-fictional-2026-11}. The pages then show a
 * banner that says trademark checking is not real. It is decided from the configured version only, so it holds whatever the
 * state of the catalog (ready, refused or not yet loaded).
 */
@ControllerAdvice(basePackages = "com.thanhdatpb.listingguard.api.console")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleModelAdvice {

    static final String DEMO_PREFIX = TrademarkProperties.DEMO_PREFIX;

    private final boolean demoCatalog;

    public ConsoleModelAdvice(TrademarkProperties trademark) {
        this.demoCatalog = trademark.demoCatalog();
    }

    static boolean isDemo(String catalogVersion) {
        return TrademarkProperties.isDemo(catalogVersion);
    }

    @ModelAttribute("demoCatalog")
    public boolean demoCatalog() {
        return demoCatalog;
    }
}

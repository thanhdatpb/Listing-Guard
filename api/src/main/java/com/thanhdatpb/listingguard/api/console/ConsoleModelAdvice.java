package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.brand.BrandAssets;
import com.thanhdatpb.listingguard.api.trademark.TrademarkProperties;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>{@code assetVersion} is the {@code ?v=} of the style sheets, logos and icons the shared layout links (ADR-040).
 */
@ControllerAdvice(basePackages = "com.thanhdatpb.listingguard.api.console")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleModelAdvice {

    static final String DEMO_PREFIX = TrademarkProperties.DEMO_PREFIX;

    private final boolean demoCatalog;
    private final String assetVersion;

    private final boolean reviewQueueEnabled;

    public ConsoleModelAdvice(TrademarkProperties trademark, BrandAssets assets,
                              @Value("${listing-guard.review-cases.enabled:false}") boolean reviewQueueEnabled) {
        this.reviewQueueEnabled = reviewQueueEnabled;
        this.demoCatalog = trademark.demoCatalog();
        this.assetVersion = assets.version();
    }

    static boolean isDemo(String catalogVersion) {
        return TrademarkProperties.isDemo(catalogVersion);
    }

    @ModelAttribute("demoCatalog")
    public boolean demoCatalog() {
        return demoCatalog;
    }

    @ModelAttribute("reviewQueueEnabled")
    public boolean reviewQueueEnabled() {
        return reviewQueueEnabled;
    }

    @ModelAttribute("assetVersion")
    public String assetVersion() {
        return assetVersion;
    }
}

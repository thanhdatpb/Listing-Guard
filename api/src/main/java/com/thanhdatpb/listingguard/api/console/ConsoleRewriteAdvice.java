package com.thanhdatpb.listingguard.api.console;

import com.thanhdatpb.listingguard.api.rewrite.ListingRewriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Tells every console template whether to show the "Gợi ý sửa" buttons (ADR-037): true only when the feature is on and a provider is
 * configured. With the feature off (the default) there is no {@link ListingRewriter} bean, the answer is always false, and a page
 * carries no button, no hidden listing and no script of the feature.
 */
@ControllerAdvice(basePackages = "com.thanhdatpb.listingguard.api.console")
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleRewriteAdvice {

    private final ObjectProvider<ListingRewriter> rewriter;

    public ConsoleRewriteAdvice(ObjectProvider<ListingRewriter> rewriter) {
        this.rewriter = rewriter;
    }

    @ModelAttribute("rewriteAvailable")
    public boolean rewriteAvailable() {
        ListingRewriter bean = rewriter.getIfAvailable();
        return bean != null && bean.available();
    }
}

package com.thanhdatpb.listingguard.api.workspace.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/**
 * A bean that exists only while the background audit is on (ADR-061): {@code listing-guard.workspace.audit.enabled}, with the workspace
 * ({@code listing-guard.workspace.enabled}) and the Console, which the audit reads and shows.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnExpression("${listing-guard.workspace.enabled:false} && ${listing-guard.console.enabled:false} "
        + "&& ${listing-guard.workspace.audit.enabled:false}")
public @interface AuditEnabled {
}

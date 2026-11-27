package com.thanhdatpb.listingguard.api.workspace.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

/** A bean that exists only while the audit's e-mail alerts are on (ADR-061): the audit's conditions and {@code listing-guard.workspace.alerts.email.enabled}. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@ConditionalOnExpression("${listing-guard.workspace.enabled:false} && ${listing-guard.console.enabled:false} "
        + "&& ${listing-guard.workspace.audit.enabled:false} && ${listing-guard.workspace.alerts.email.enabled:false}")
public @interface AlertsEnabled {
}

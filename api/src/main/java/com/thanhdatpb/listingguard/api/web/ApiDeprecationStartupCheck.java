package com.thanhdatpb.listingguard.api.web;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Stops the start-up when a deprecation setting names a method and path that is not an operation of this application
 * (ADR-022, section 6): a typo would otherwise announce nothing, silently.
 */
final class ApiDeprecationStartupCheck implements SmartInitializingSingleton {

    private final ApiDeprecations deprecations;
    private final RequestMappingHandlerMapping mappings;

    ApiDeprecationStartupCheck(ApiDeprecations deprecations, RequestMappingHandlerMapping mappings) {
        this.deprecations = deprecations;
        this.mappings = mappings;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!deprecations.endpoints().isEmpty()) {
            check(deprecations, operations(mappings));
        }
    }

    static void check(ApiDeprecations deprecations, Set<String> operations) {
        List<String> unknown = deprecations.unknownOperations(operations);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("listing-guard.api.deprecation.endpoints names operations this application does not have: "
                    + unknown + " (ADR-022). Use the method and the path exactly as docs/api/openapi.json writes them.");
        }
    }

    /** Every mapped operation as {@code METHOD /path/template}. */
    static Set<String> operations(RequestMappingHandlerMapping mappings) {
        Set<String> operations = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.values()) : methods) {
                    operations.add(method.name() + " " + pattern);
                }
            }
        }
        return operations;
    }
}

package com.thanhdatpb.listingguard.api.demo;

import com.thanhdatpb.listingguard.api.console.ConsoleUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** A previously seeded demo account cannot use the real pipeline after demo mode is switched off. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class DemoConsoleAccess implements WebMvcConfigurer {
    private final DemoWorkspaceRegistry workspaces;

    public DemoConsoleAccess(DemoWorkspaceRegistry workspaces) {
        this.workspaces = workspaces;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                    throws java.io.IOException {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (!workspaces.enabled() && authentication != null
                        && authentication.getPrincipal() instanceof ConsoleUser user && workspaces.marked(user.clientId())) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return false;
                }
                return true;
            }
        }).addPathPatterns("/console", "/console/**");
    }
}

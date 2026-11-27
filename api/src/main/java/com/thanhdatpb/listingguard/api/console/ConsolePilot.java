package com.thanhdatpb.listingguard.api.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Pilot presentation and server-side Console access boundaries. */
@Component
public class ConsolePilot implements WebMvcConfigurer {
    private final Environment environment;
    private final ConsoleAccountRoles roles;

    public ConsolePilot(Environment environment, ConsoleAccountRoles roles) {
        this.environment = environment;
        this.roles = roles;
    }

    public boolean enabled() {
        return environment.getProperty("listing-guard.console.pilot", Boolean.class, false);
    }

    public boolean roleHome() {
        return enabled() && flag("console.role-home-enabled", false);
    }

    public boolean policyChanges() {
        return flag("console.policy-changes-enabled", true);
    }

    public boolean pod() {
        return flag("console.pod-enabled", true);
    }

    public boolean review() {
        return flag("review-cases.enabled", false);
    }

    private boolean flag(String name, boolean fallback) {
        return environment.getProperty("listing-guard." + name, Boolean.class, fallback);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws java.io.IOException {
                if (!enabled()) {
                    return true;
                }
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.getPrincipal() instanceof ConsoleUser user && roles.isAdmin(user)) {
                    return true;
                }
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
        }).addPathPatterns("/console/watchlist", "/console/watchlist/**", "/console/stats", "/console/stats.csv",
                "/console/stats/pilot", "/console/stats/pilot.csv", "/console/api-keys", "/console/api-keys/**",
                "/console/report", "/console/report/**", "/console/report.*", "/console/readiness");
    }
}

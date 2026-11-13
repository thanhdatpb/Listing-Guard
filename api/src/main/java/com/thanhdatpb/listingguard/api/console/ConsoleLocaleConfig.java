package com.thanhdatpb.listingguard.api.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the console's language (ADR-028): the {@code localeResolver} the dispatcher uses to pick the language of a view, and the
 * interceptor that turns a {@code ?lang=} link into the cookie. Present only while the console is on, so with it off the
 * application keeps the resolver Spring Boot gives it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "listing-guard.console", name = "enabled", havingValue = "true")
public class ConsoleLocaleConfig {

    /** The bean name is the one {@code DispatcherServlet} looks for. */
    @Bean(name = "localeResolver")
    public ConsoleLocaleResolver localeResolver(ConsoleLocales locales, ConsoleProperties properties) {
        return new ConsoleLocaleResolver(locales, properties.cookieSecure());
    }

    @Bean
    public WebMvcConfigurer consoleLocaleInterceptor(ConsoleLocaleResolver resolver) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new HandlerInterceptor() {
                    @Override
                    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                        resolver.remember(request, response);
                        return true;
                    }
                }).addPathPatterns("/console", "/console/**", "/login", "/login/**");
            }
        };
    }
}

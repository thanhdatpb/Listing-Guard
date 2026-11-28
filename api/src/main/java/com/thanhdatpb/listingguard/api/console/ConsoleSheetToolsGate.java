package com.thanhdatpb.listingguard.api.console;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * The paths of the Sheet tools answer 404 while the workspace console hides them (ADR-064 §2): the bulk check, the before/after edit and its
 * policy check, the re-audit by file and the history of checks. A filter, not a handler interceptor, so that every method and every state
 * of a request gets the same answer (a GET of a path that only takes POST would otherwise be a 405, and a POST without a token a 403). The
 * single check ({@code /console/check}) is not among them: the workspace console keeps it as "Quick check". Nothing is registered in the
 * classic console, so a path of it is answered by its controller as before.
 */
@Configuration(proxyBeanMethods = false)
public class ConsoleSheetToolsGate {

    /** The servlet URL patterns the gate answers for; a test keeps the menu and the links of the pages in step with this list. */
    public static final String[] PATHS = {
            "/console/bulk", "/console/bulk/*",
            "/console/check/edit", "/console/check/edit/*",
            "/console/reaudit", "/console/reaudit/*",
            "/console/history", "/console/history/*"
    };

    @Bean
    public FilterRegistrationBean<Filter> consoleSheetToolsGateFilter(ConsoleMode mode) {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new NotFound());
        registration.addUrlPatterns(PATHS);
        registration.setName("consoleSheetToolsGate");
        // before the security chain: a hidden path is not found for anybody, signed in or not
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 50);
        registration.setEnabled(mode.sheetToolsHidden());
        return registration;
    }

    static final class NotFound implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}

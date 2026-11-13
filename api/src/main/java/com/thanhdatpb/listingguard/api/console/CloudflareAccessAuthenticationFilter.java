package com.thanhdatpb.listingguard.api.console;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

public final class CloudflareAccessAuthenticationFilter extends OncePerRequestFilter {
    private final CloudflareAccessLoginService login;
    private final ConsoleProperties properties;
    private final HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public CloudflareAccessAuthenticationFilter(CloudflareAccessLoginService login, ConsoleProperties properties) {
        this.login = login;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String consolePath = request.getContextPath() + "/console";
        if (properties.cloudflareAccessEnabled() && request.getRequestURI().equals(consolePath)) {
            response.sendRedirect(consolePath + "/");
            return;
        }
        if (properties.cloudflareAccessEnabled() && (request.getRequestURI().equals(consolePath)
                || request.getRequestURI().startsWith(consolePath + "/"))
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            var user = login.authenticate(request.getHeader("Cf-Access-Jwt-Assertion"));
            if (user.isPresent()) {
                request.getSession(true);
                request.changeSessionId();
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.get(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_CONSOLE"))));
                SecurityContextHolder.setContext(context);
                repository.saveContext(context, request, response);
            } else {
                response.sendRedirect("/login?access=denied");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

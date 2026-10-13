package com.bejo.admin.web;

import com.bejo.admin.service.AdminAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Optional;

/**
 * Guards the admin dashboard's API ({@code /api/admin/**}) with the session token issued by
 * {@link AdminAuthService} ({@code Authorization: Bearer …}). Only the two sign-in endpoints under
 * {@code /api/admin/auth/otp/} are open. Like every route these still need {@code X-API-TOKEN} —
 * {@code ApiTokenFilter} runs first.
 *
 * <p>A filter rather than Spring Security's user-JWT chain because an admin is not an app user: the
 * routes are {@code permitAll} there and this is their only gate. It answers 401 (the dashboard signs
 * itself out on that) and exposes the admin's address as the {@link #ADMIN_EMAIL} request attribute.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AdminAuthFilter extends OncePerRequestFilter {

    public static final String ADMIN_EMAIL = "bejo.adminEmail";
    public static final String ADMIN_TOKEN = "bejo.adminToken";

    private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);
    private static final String PATH_PREFIX = "/api/admin";
    private static final String OPEN_PREFIX = "/api/admin/auth/otp/";
    private static final String BEARER = "Bearer ";

    // This filter runs before Spring Security's firewall, so the raw URI is not necessarily the path
    // Spring MVC will map (percent-encoding, path parameters, double slashes). Guard when EITHER the raw
    // or the normalized path is on the admin surface, and treat a request as open only when BOTH agree.
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final AdminAuthService authService;

    public AdminAuthFilter(AdminAuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!guardsThisRequest(request)) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()).trim() : null;
        Optional<String> admin = authService.authenticate(token);
        if (admin.isEmpty()) {
            log.warn("Rejected {} — missing, expired or revoked admin session", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":0,\"message\":\"Sesi admin tidak valid atau sudah berakhir\"}");
            return;
        }
        request.setAttribute(ADMIN_EMAIL, admin.get());
        request.setAttribute(ADMIN_TOKEN, token);
        chain.doFilter(request, response);
    }

    private boolean guardsThisRequest(HttpServletRequest request) {
        String raw = request.getRequestURI();
        String normalized;
        try {
            normalized = PATH_HELPER.getPathWithinApplication(request);
        } catch (RuntimeException e) {
            // Unparseable path: treat as guarded rather than letting it through unchecked.
            log.warn("Could not normalize request path — guarding it: {}", e.getMessage());
            return true;
        }
        if (!raw.startsWith(PATH_PREFIX) && !normalized.startsWith(PATH_PREFIX)) {
            return false;
        }
        boolean open = raw.startsWith(OPEN_PREFIX) && normalized.startsWith(OPEN_PREFIX)
                && !raw.contains("..") && !normalized.contains("..");
        return !open;
    }
}

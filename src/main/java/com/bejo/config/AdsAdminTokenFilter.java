package com.bejo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Guards the ads admin surface ({@code /api/ads/campaigns/**}) with the dedicated
 * {@code X-ADS-ADMIN-TOKEN} header. Runs as a filter — before Spring MVC binds/validates the
 * request body — so an unauthorized caller gets a {@code 403} rather than a body-validation
 * {@code 400} that would leak the request shape. Ordered just after {@link ApiTokenFilter}
 * (which enforces the shared {@code X-API-TOKEN} first). The device endpoints
 * ({@code /api/ads/deliveries/**}) are untouched here — they use the normal JWT.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AdsAdminTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdsAdminTokenFilter.class);
    private static final String HEADER = "X-ADS-ADMIN-TOKEN";
    private static final String PATH_PREFIX = "/api/ads/campaigns";

    @Value("${app.ads.admin-token:}")
    private String adminToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(PATH_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        // A blank configured token means the feature isn't enabled — reject, don't accept-anything.
        if (adminToken == null || adminToken.isBlank()) {
            reject(response, "Fitur iklan belum diaktifkan");
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), adminToken.getBytes(StandardCharsets.UTF_8))) {
            log.warn("Rejected {} — missing or invalid {}", request.getRequestURI(), HEADER);
            reject(response, "Token admin iklan tidak valid");
            return;
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":0,\"message\":\"" + message + "\"}");
    }
}

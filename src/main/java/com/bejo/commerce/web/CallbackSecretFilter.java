package com.bejo.commerce.web;

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
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Optionally authenticates the inbound QRIS payment callback ({@code /snap/**}) with a shared
 * secret in the {@code X-Callback-Secret} header.
 *
 * <p>That endpoint is exempt from the API token and JWT (the provider is a server, not the app),
 * which leaves it callable by anyone who can reach it — and a forged callback records a payment
 * and pushes a "payment received" alert to the merchant's phones. When
 * {@code app.callback.secret} ({@code CALLBACK_SECRET}) is set, this filter rejects every
 * request to the path that does not carry it. When it is blank the filter does nothing, so the
 * existing provider integration keeps working until the provider is configured to send the
 * header; a prod startup warning ({@code ProdSecretsEnvironmentPostProcessor}) reminds you.
 *
 * <p>It is a filter, not a controller check, so an unauthorized caller gets a {@code 401}
 * before Spring MVC parses and validates the body — otherwise a {@code 400} would leak the
 * request shape (same reasoning as {@code AdsAdminTokenFilter}). The comparison is constant-time.
 * If the provider cannot send a custom header, restrict {@code /snap/**} to the provider's source
 * IPs at the edge instead (Caddy {@code remote_ip}, Cloud Armor).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class CallbackSecretFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CallbackSecretFilter.class);
    static final String HEADER = "X-Callback-Secret";
    private static final String PATH_PREFIX = "/snap/";

    // Runs before Spring Security's firewall, so check the decoded/normalized path as well as the
    // raw one and guard if EITHER targets the callback — an encoded variant that still reaches the
    // controller then can't skip the check.
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    @Value("${app.callback.secret:}")
    private String secret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (secret == null || secret.isBlank() || !guardsThisRequest(request)) {
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
            log.warn("[CALLBACK] Rejected {} — missing or invalid {}", request.getRequestURI(), HEADER);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":0,\"message\":\"Missing or invalid " + HEADER + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean guardsThisRequest(HttpServletRequest request) {
        if (request.getRequestURI().startsWith(PATH_PREFIX)) {
            return true;
        }
        try {
            return PATH_HELPER.getPathWithinApplication(request).startsWith(PATH_PREFIX);
        } catch (RuntimeException e) {
            // Unparseable path: treat as guarded rather than letting it through unchecked.
            log.warn("Could not normalize request path — guarding it: {}", e.getMessage());
            return true;
        }
    }
}

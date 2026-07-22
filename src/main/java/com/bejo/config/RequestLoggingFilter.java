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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    // The unauthenticated QRIS payment callback — on any failure we want the full
    // body + all headers to debug what the merchant service actually sent.
    private static final String CALLBACK_PATH = "/snap/v1.0/qr/qr-mpm-notify";
    private static final int CALLBACK_MAX_BODY = 8192;

    // Mask secret values so credentials never reach the logs, even on a captured body
    private static final Pattern SENSITIVE_FIELDS = Pattern.compile(
            "(\"(?:password|oldPassword|newPassword|pushToken|token)\"\\s*:\\s*\")(.*?)(\")",
            Pattern.CASE_INSENSITIVE);

    // Header values never worth logging in the clear (masked even for the callback dump)
    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "x-api-token", "cookie", "set-cookie");

    // Bodies for these paths are almost entirely secret — never capture them
    private static boolean isSensitivePath(String uri) {
        return uri.startsWith("/api/auth/")
                || uri.equals("/api/users/register")
                || uri.equals("/api/users/members")   // add-member carries a password
                || uri.startsWith("/api/users/members/");
    }

    // Capture request bodies only on 4xx/5xx (default on; disable via env). Keeps normal
    // traffic quiet and cheap while making failed requests debuggable in Cloud Run.
    @Value("${app.request-logging.log-body-on-error:true}")
    private boolean logBodyOnError;

    @Value("${app.request-logging.max-body-length:2048}")
    private int maxBodyLength;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        boolean isCallback = CALLBACK_PATH.equals(uri);

        HttpServletRequest req;
        if (isCallback) {
            // Buffer the body up front so it is available even if validation fails
            // before the body is read (e.g. a missing required header).
            req = new CachedBodyHttpServletRequest(request);
        } else if (logBodyOnError && !isSensitivePath(uri)) {
            // Cap the cached body at the max we'd ever log — bounds memory + non-deprecated ctor
            req = new ContentCachingRequestWrapper(request, maxBodyLength);
        } else {
            req = request;
        }

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(req, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            String query = request.getQueryString();
            int status = response.getStatus();
            log.info("{} {}{} {} {}ms",
                    request.getMethod(), uri, query != null ? "?" + query : "", status, duration);

            if (isCallback && status >= 400) {
                // Error only: full request dump (every header + the whole body) at WARN, to debug a
                // rejected callback. The success path is logged by QrisCallbackService (the key
                // fields), so no verbose body dump is needed there.
                log.warn("[CALLBACK-REQ] {} {} status={} headers=[{}] body={}",
                        request.getMethod(), uri, status,
                        headers(request), callbackBody((CachedBodyHttpServletRequest) req));
            } else if (req instanceof ContentCachingRequestWrapper wrapper && status >= 400) {
                String body = extractBody(wrapper);
                if (!body.isBlank()) {
                    log.warn("[BODY] {} {} — {}", request.getMethod(), uri, body);
                }
            }
        }
    }

    // All headers, single-line, with credential headers masked
    private String headers(HttpServletRequest request) {
        return Collections.list(request.getHeaderNames()).stream()
                .map(name -> name + ": " + (SENSITIVE_HEADERS.contains(name.toLowerCase())
                        ? "***" : String.join(",", Collections.list(request.getHeaders(name)))))
                .collect(Collectors.joining(", "));
    }

    // Full callback body, single-line, capped only to bound abuse (the endpoint is public)
    private String callbackBody(CachedBodyHttpServletRequest request) {
        byte[] buf = request.getBody();
        if (buf.length == 0) return "<empty>";
        String body = new String(buf, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        if (body.length() > CALLBACK_MAX_BODY) {
            body = body.substring(0, CALLBACK_MAX_BODY) + "…(truncated)";
        }
        return body;
    }

    // Redact secrets, collapse whitespace to a single line, and cap the length so the
    // captured body is one bounded, credential-free JSON log entry.
    private String extractBody(ContentCachingRequestWrapper wrapper) {
        byte[] buf = wrapper.getContentAsByteArray();
        if (buf.length == 0) return "";
        String body = new String(buf, StandardCharsets.UTF_8);
        body = SENSITIVE_FIELDS.matcher(body).replaceAll("$1***$3");
        body = body.replaceAll("\\s+", " ").trim();
        if (body.length() > maxBodyLength) {
            body = body.substring(0, maxBodyLength) + "…(truncated)";
        }
        return body;
    }
}

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
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    // Mask secret values so credentials never reach the logs, even on a captured body
    private static final Pattern SENSITIVE_FIELDS = Pattern.compile(
            "(\"(?:password|oldPassword|newPassword|pushToken|token)\"\\s*:\\s*\")(.*?)(\")",
            Pattern.CASE_INSENSITIVE);

    // Bodies for these paths are almost entirely secret — never capture them
    private static boolean isSensitivePath(String uri) {
        return uri.startsWith("/api/auth/")
                || uri.equals("/api/users/register")
                || uri.equals("/api/users/internal/register");
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
        boolean captureBody = logBodyOnError && !isSensitivePath(uri);
        HttpServletRequest req = captureBody ? new ContentCachingRequestWrapper(request) : request;

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(req, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            String query = request.getQueryString();
            log.info("{} {}{} {} {}ms",
                    request.getMethod(),
                    uri,
                    query != null ? "?" + query : "",
                    response.getStatus(),
                    duration);

            if (captureBody && response.getStatus() >= 400) {
                String body = extractBody((ContentCachingRequestWrapper) req);
                if (!body.isBlank()) {
                    log.warn("[BODY] {} {} — {}", request.getMethod(), uri, body);
                }
            }
        }
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

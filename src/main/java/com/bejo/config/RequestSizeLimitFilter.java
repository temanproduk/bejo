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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects oversized request bodies with {@code 413} before Spring reads or buffers them.
 *
 * <p>Tomcat's {@code maxPostSize} only covers form encoding, so a JSON body is otherwise
 * unbounded — and several endpoints take base64 images that are decoded in memory on a 512 Mi
 * container. The cap ({@code app.security.max-request-bytes}) is set just above the largest
 * legitimate payload, the ~7 MB base64 QRIS image accepted by
 * {@code UploadQrisRequestDto}/{@code SetUserQrisRequestDto}; every other body (≤2 MB ad audio,
 * ≤1 MB business picture, ≤500 KB product photo, bulk transaction batches) is far smaller.
 *
 * <p>Enforced from {@code Content-Length}, which any HTTP client sending a JSON body provides.
 * A chunked upload without that header can't be pre-checked here — Cloud Run's own request
 * ceiling is the backstop for that case.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestSizeLimitFilter.class);

    @Value("${app.security.max-request-bytes:8388608}")
    private long maxRequestBytes;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxRequestBytes) {
            log.warn("Rejected {} {} — body {} bytes exceeds the {} byte limit",
                    request.getMethod(), request.getRequestURI(), declared, maxRequestBytes);
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"status\":0,\"message\":\"Ukuran permintaan terlalu besar\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}

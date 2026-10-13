package com.bejo.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLoggingFilterTest {

    private RequestLoggingFilter filter;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logbackLogger;

    @BeforeEach
    void setUp() {
        filter = new RequestLoggingFilter();
        ReflectionTestUtils.setField(filter, "logBodyOnError", true);
        ReflectionTestUtils.setField(filter, "maxBodyLength", 2048);

        logbackLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
        logbackLogger.setLevel(null);   // reset so a DEBUG-level test doesn't leak into others
    }

    // Chain that consumes the body (so it gets cached) and sets a status
    private FilterChain chainReturning(int status) {
        return (req, res) -> {
            req.getInputStream().readAllBytes();
            ((HttpServletResponse) res).setStatus(status);
        };
    }

    private MockHttpServletRequest post(String uri, String body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setContent(body.getBytes());
        req.setContentType("application/json");
        return req;
    }

    private List<String> bodyLogs() {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("[BODY]"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void logsBodyOnErrorForNonSensitivePath() throws Exception {
        MockHttpServletRequest req = post("/api/transactions", "{\"amount\":50000,\"type\":\"MANUAL\"}");

        filter.doFilter(req, new MockHttpServletResponse(), chainReturning(400));

        assertThat(bodyLogs()).hasSize(1);
        assertThat(bodyLogs().getFirst()).contains("/api/transactions").contains("\"amount\":50000");
    }

    @Test
    void doesNotLogBodyOnSuccess() throws Exception {
        MockHttpServletRequest req = post("/api/transactions", "{\"amount\":50000}");

        filter.doFilter(req, new MockHttpServletResponse(), chainReturning(200));

        assertThat(bodyLogs()).isEmpty();
    }

    @Test
    void skipsBodyForAuthPaths() throws Exception {
        MockHttpServletRequest req = post("/api/auth/login", "{\"username\":\"a\",\"password\":\"secret\"}");

        filter.doFilter(req, new MockHttpServletResponse(), chainReturning(400));

        assertThat(bodyLogs()).isEmpty();
    }

    @Test
    void redactsSensitiveFieldsInBody() throws Exception {
        MockHttpServletRequest req = post("/api/devices/dev-1/fcm-token", "{\"pushToken\":\"super-secret-token\"}");

        filter.doFilter(req, new MockHttpServletResponse(), chainReturning(400));

        assertThat(bodyLogs()).hasSize(1);
        assertThat(bodyLogs().getFirst())
                .contains("\"pushToken\":\"***\"")
                .doesNotContain("super-secret-token");
    }

    private List<String> callbackLogs() {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("[CALLBACK-REQ]"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void callbackErrorDumpsFullBodyAndHeadersEvenWhenBodyNotRead() throws Exception {
        MockHttpServletRequest req = post("/snap/v1.0/qr/qr-mpm-notify",
                "{\"originalReferenceNo\":\"XCBS6Z450S\",\"latestTransactionStatus\":\"00\"}");
        req.addHeader("X-PARTNER-ID", "astrapay");

        // Chain does NOT read the body — simulates a missing-header validation failure
        filter.doFilter(req, new MockHttpServletResponse(),
                (r, res) -> ((HttpServletResponse) res).setStatus(400));

        assertThat(callbackLogs()).hasSize(1);
        assertThat(callbackLogs().getFirst())
                .contains("X-PARTNER-ID: astrapay")
                .contains("\"originalReferenceNo\":\"XCBS6Z450S\"");
    }

    @Test
    void callbackMasksCredentialHeaders() throws Exception {
        MockHttpServletRequest req = post("/snap/v1.0/qr/qr-mpm-notify", "{}");
        req.addHeader("Authorization", "Bearer super-secret");
        req.addHeader("X-API-TOKEN", "the-shared-secret");
        req.addHeader("X-Callback-Secret", "the-callback-secret");

        filter.doFilter(req, new MockHttpServletResponse(), chainReturning(400));

        assertThat(callbackLogs().getFirst())
                .contains("Authorization: ***")
                .contains("X-API-TOKEN: ***")
                .contains("X-Callback-Secret: ***")
                .doesNotContain("super-secret")
                .doesNotContain("the-shared-secret")
                .doesNotContain("the-callback-secret");
    }

    @Test
    void callbackSuccessDoesNotDump() throws Exception {
        // A successful callback produces no [CALLBACK-REQ] dump (the service logs its key fields);
        // only an error (below) dumps the full request.
        logbackLogger.setLevel(Level.DEBUG);
        MockHttpServletRequest req = post("/snap/v1.0/qr/qr-mpm-notify", "{\"foo\":\"bar\"}");

        filter.doFilter(req, new MockHttpServletResponse(), chainReturning(200));

        assertThat(callbackLogs()).isEmpty();
    }

    @Test
    void alwaysLogsRequestLineWithQueryString() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/transactions");
        req.setQueryString("dateStart=2026-07-01&dateEnd=2026-07-31");

        filter.doFilter(req, new MockHttpServletResponse(), chainReturning(200));

        List<String> reqLines = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertThat(reqLines).anyMatch(l -> l.contains("/api/transactions?dateStart=2026-07-01&dateEnd=2026-07-31"));
    }
}

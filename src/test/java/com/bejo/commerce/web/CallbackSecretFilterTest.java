package com.bejo.commerce.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CallbackSecretFilterTest {

    private static final String CALLBACK = "/snap/v1.0/qr/qr-mpm-notify";

    private CallbackSecretFilter filter;
    private int chainCalls;
    private final FilterChain chain = (req, res) -> chainCalls++;

    @BeforeEach
    void setUp() {
        filter = new CallbackSecretFilter();
        chainCalls = 0;
    }

    private MockHttpServletRequest post(String uri, String secretHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        if (secretHeader != null) req.addHeader("X-Callback-Secret", secretHeader);
        return req;
    }

    private MockHttpServletResponse run(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        return res;
    }

    @Test
    void passesEverythingThroughWhenNoSecretIsConfigured() throws Exception {
        ReflectionTestUtils.setField(filter, "secret", "");

        MockHttpServletResponse res = run(post(CALLBACK, null));

        assertThat(chainCalls).isEqualTo(1);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsACallbackWithoutTheSecret() throws Exception {
        ReflectionTestUtils.setField(filter, "secret", "s3cret-value");

        MockHttpServletResponse res = run(post(CALLBACK, null));

        assertThat(chainCalls).isZero();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"status\":0");
    }

    @Test
    void rejectsACallbackWithTheWrongSecret() throws Exception {
        ReflectionTestUtils.setField(filter, "secret", "s3cret-value");

        MockHttpServletResponse res = run(post(CALLBACK, "not-it"));

        assertThat(chainCalls).isZero();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).doesNotContain("s3cret-value");
    }

    @Test
    void acceptsACallbackWithTheRightSecret() throws Exception {
        ReflectionTestUtils.setField(filter, "secret", "s3cret-value");

        MockHttpServletResponse res = run(post(CALLBACK, "s3cret-value"));

        assertThat(chainCalls).isEqualTo(1);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void leavesEveryOtherPathAlone() throws Exception {
        ReflectionTestUtils.setField(filter, "secret", "s3cret-value");

        run(post("/api/auth/login", null));
        run(post("/api/transactions", null));
        run(post("/actuator/health", null));

        assertThat(chainCalls).isEqualTo(3);
    }

    @Test
    void guardsEncodedAndDoubleSlashVariantsOfTheCallbackPath() throws Exception {
        ReflectionTestUtils.setField(filter, "secret", "s3cret-value");

        // The raw URI does not start with /snap/, but the decoded/sanitized path does: still guarded
        MockHttpServletResponse encoded = run(post("/%73nap/v1.0/qr/qr-mpm-notify", null));
        MockHttpServletResponse doubleSlash = run(post("//snap/v1.0/qr/qr-mpm-notify", null));

        assertThat(chainCalls).isZero();
        assertThat(encoded.getStatus()).isEqualTo(401);
        assertThat(doubleSlash.getStatus()).isEqualTo(401);
    }
}

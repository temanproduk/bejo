package com.bejo.web;

import com.bejo.notification.InMemoryEmailSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration now ends with an unverified account that cannot log in. The functional tests that register
 * users and then use them finish the flow here: read the code out of the "email" (the recording
 * {@link InMemoryEmailSender}) and confirm it through the real endpoint.
 */
final class EmailVerificationTestSupport {

    private EmailVerificationTestSupport() {}

    /** Confirms the address the account registered with, using the code that was emailed to it. */
    static void verifyEmail(MockMvc mockMvc, ObjectMapper objectMapper, InMemoryEmailSender email,
                            String username, String address) throws Exception {
        String code = email.lastCodeTo(address);
        assertThat(code).as("a verification code was emailed to " + address).matches("\\d{6}");
        mockMvc.perform(post("/api/auth/verify-email").header("X-API-TOKEN", "test-api-token")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "code", code))))
                .andExpect(status().isOk());
    }
}

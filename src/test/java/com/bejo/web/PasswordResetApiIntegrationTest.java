package com.bejo.web;

import com.bejo.notification.InMemoryEmailSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The forgotten-password flow end to end through the real endpoints: the code goes out in an "email"
 * (the recording {@link InMemoryEmailSender}), is read back, and finishes the reset.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";
    private static final String NEW_PASSWORD = "BrandNew99";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InMemoryEmailSender email;

    @AfterEach
    void restoreMailer() {
        email.setEnabled(true);
        email.setFailing(false);
    }

    // ---------- helpers ----------

    private String registerAndLogin(String username) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", "password1");
        body.put("name", "Test User");
        body.put("email", username + "@example.com");
        body.put("phone", "+628123456789");
        body.put("businessName", "Warung " + username);
        mockMvc.perform(post("/api/users/register").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        return login(username, "password1").andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").header("X-API-TOKEN", API_TOKEN)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "username", username, "password", password, "deviceId", "dev-" + username))));
    }

    private ResultActions forgot(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/forgot-password").header("X-API-TOKEN", API_TOKEN)
                .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("username", username))));
    }

    private ResultActions reset(String username, String code, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/auth/reset-password").header("X-API-TOKEN", API_TOKEN)
                .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                        "username", username, "code", code, "newPassword", newPassword))));
    }

    private String wrongCodeFor(String realCode) {
        return realCode.equals("000000") ? "111111" : "000000";
    }

    // ---------- the happy path ----------

    @Test
    void forgotThenResetChangesThePasswordAndEndsEverySession() throws Exception {
        String oldToken = registerAndLogin("pr_happy");

        forgot("pr_happy").andExpect(status().isOk()).andExpect(jsonPath("$.status").value(1));
        String code = email.lastCodeTo("pr_happy@example.com");
        assertThat(code).as("a code was emailed").matches("\\d{6}");

        reset("pr_happy", code, NEW_PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(1));

        login("pr_happy", NEW_PASSWORD).andExpect(status().isOk());                 // new password works
        login("pr_happy", "password1").andExpect(status().isBadRequest());          // old one does not
        // the pre-reset session is dead: the token's password-binding claim no longer matches
        mockMvc.perform(get("/api/transactions").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aCodeWorksOnlyOnce() throws Exception {
        registerAndLogin("pr_once");
        forgot("pr_once").andExpect(status().isOk());
        String code = email.lastCodeTo("pr_once@example.com");

        reset("pr_once", code, NEW_PASSWORD).andExpect(status().isOk());
        reset("pr_once", code, "AnotherOne77").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Kode tidak valid atau sudah kedaluwarsa"));
        login("pr_once", NEW_PASSWORD).andExpect(status().isOk());   // the second attempt changed nothing
    }

    // ---------- wrong codes ----------

    @Test
    void aWrongCodeIsRejectedAndTheAccountIsUntouched() throws Exception {
        registerAndLogin("pr_wrong");
        forgot("pr_wrong").andExpect(status().isOk());
        String code = email.lastCodeTo("pr_wrong@example.com");

        reset("pr_wrong", wrongCodeFor(code), NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Kode tidak valid atau sudah kedaluwarsa"));

        login("pr_wrong", "password1").andExpect(status().isOk());
    }

    @Test
    void afterFiveWrongGuessesEvenTheRightCodeIsDead() throws Exception {
        registerAndLogin("pr_brute");
        forgot("pr_brute").andExpect(status().isOk());
        String code = email.lastCodeTo("pr_brute@example.com");

        for (int i = 0; i < 5; i++) {
            reset("pr_brute", wrongCodeFor(code), NEW_PASSWORD).andExpect(status().isBadRequest());
        }
        reset("pr_brute", code, NEW_PASSWORD).andExpect(status().isBadRequest());   // too late

        login("pr_brute", "password1").andExpect(status().isOk());                  // password unchanged
    }

    @Test
    void resettingAnUnknownUserLooksLikeAnyOtherBadCode() throws Exception {
        reset("pr_nobody", "123456", NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Kode tidak valid atau sudah kedaluwarsa"));
    }

    // ---------- no account enumeration, rate limits ----------

    @Test
    void forgotPasswordAnswersIdenticallyForKnownAndUnknownUsers() throws Exception {
        registerAndLogin("pr_known");

        String known = forgot("pr_known").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String unknown = forgot("pr_does_not_exist").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(known);
        assertThat(email.sentTo("pr_does_not_exist@example.com")).isEmpty();
    }

    @Test
    void aSecondRequestInsideTheCooldownSendsNothingNew() throws Exception {
        registerAndLogin("pr_cool");

        forgot("pr_cool").andExpect(status().isOk());
        forgot("pr_cool").andExpect(status().isOk());   // same answer, but suppressed

        assertThat(email.sentTo("pr_cool@example.com")).hasSize(1);
    }

    @Test
    void aSuccessfulResetLiftsALoginLockout() throws Exception {
        registerAndLogin("pr_lock");
        for (int i = 0; i < 3; i++) {           // application-test.yml locks after 3 failures
            login("pr_lock", "wrong-password1").andExpect(status().isBadRequest());
        }
        login("pr_lock", "password1").andExpect(status().isTooManyRequests());

        forgot("pr_lock").andExpect(status().isOk());
        reset("pr_lock", email.lastCodeTo("pr_lock@example.com"), NEW_PASSWORD).andExpect(status().isOk());

        login("pr_lock", NEW_PASSWORD).andExpect(status().isOk());
    }

    // ---------- the stolen-session guard ----------

    @Test
    void aRecentlyChangedEmailReceivesNoCode() throws Exception {
        String token = registerAndLogin("pr_swap");

        // a (possibly stolen) session swaps in a different address — this needs no password
        mockMvc.perform(patch("/api/users/me").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Test User\",\"email\":\"attacker@example.com\",\"phone\":\"+628123456789\"}"))
                .andExpect(status().isOk());

        forgot("pr_swap").andExpect(status().isOk());   // same generic answer...

        assertThat(email.sentTo("attacker@example.com")).isEmpty();   // ...but nothing is sent anywhere
        assertThat(email.sentTo("pr_swap@example.com")).isEmpty();
    }

    // ---------- configuration and validation ----------

    @Test
    void forgotPasswordIs503WhenEmailIsNotConfigured() throws Exception {
        email.setEnabled(false);

        forgot("anyone").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void aMailServerOutageIsInvisibleToTheCaller() throws Exception {
        registerAndLogin("pr_outage");
        email.setFailing(true);

        forgot("pr_outage").andExpect(status().isOk()).andExpect(jsonPath("$.status").value(1));
    }

    @Test
    void theCodeAndPasswordAreValidated() throws Exception {
        reset("someone", "12ab56", NEW_PASSWORD).andExpect(status().isBadRequest());         // not 6 digits
        reset("someone", "123456", "short1").andExpect(status().isBadRequest());             // under 8 chars
        reset("someone", "123456", "allletters").andExpect(status().isBadRequest());         // no digit
    }

    @Test
    void bothEndpointsNeedTheApiTokenButNotALogin() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/reset-password").contentType(APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"code\":\"123456\",\"newPassword\":\"NewPass123\"}"))
                .andExpect(status().isUnauthorized());
    }
}

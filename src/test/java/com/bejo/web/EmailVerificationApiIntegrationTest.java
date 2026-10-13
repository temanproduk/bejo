package com.bejo.web;

import com.bejo.notification.InMemoryEmailSender;
import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Email verification end to end through the real endpoints: a new registration gets a code by email,
 * cannot log in until it is confirmed, and email addresses are unique. The recording
 * {@link InMemoryEmailSender} stands in for SMTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailVerificationApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InMemoryEmailSender email;
    @Autowired private com.bejo.core.repository.UserRepository userRepository;

    @AfterEach
    void restoreMailer() {
        email.setEnabled(true);
        email.setFailing(false);
    }

    // ---------- helpers ----------

    private Map<String, Object> body(String username) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", "password1");
        body.put("name", "Test User");
        body.put("email", username + "@example.com");
        body.put("businessName", "Warung " + username);
        return body;
    }

    private ResultActions register(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/users/register").header("X-API-TOKEN", API_TOKEN)
                .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").header("X-API-TOKEN", API_TOKEN)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "username", username, "password", password, "deviceId", "dev-" + username))));
    }

    private ResultActions verify(String username, String code) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email").header("X-API-TOKEN", API_TOKEN)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "code", code))));
    }

    private ResultActions resend(String username) throws Exception {
        return mockMvc.perform(post("/api/auth/resend-verification").header("X-API-TOKEN", API_TOKEN)
                .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("username", username))));
    }

    private String wrongCodeFor(String realCode) {
        return realCode.equals("000000") ? "111111" : "000000";
    }

    private String registerAndLoginToken(String username) throws Exception {
        register(body(username)).andExpect(status().isCreated());
        EmailVerificationTestSupport.verifyEmail(mockMvc, objectMapper, email, username, username + "@example.com");
        JsonNode json = objectMapper.readTree(login(username, "password1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        return json.at("/data/token").asText();
    }

    // ---------- registration ----------

    @Test
    void registrationEmailsACodeAndTheAccountCannotLogInUntilItIsConfirmed() throws Exception {
        register(body("ev_happy")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.data.emailVerificationRequired").value(true))
                .andExpect(jsonPath("$.data.emailSent").value(true))
                .andExpect(jsonPath("$.data.email").value("e***@example.com"));   // masked, not the full address
        String code = email.lastCodeTo("ev_happy@example.com");
        assertThat(code).as("a code was emailed").matches("\\d{6}");

        // Right password, unverified email: refused with a machine-readable reason, and no session or token
        login("ev_happy", "password1").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("belum diverifikasi")))
                .andExpect(jsonPath("$.data.reason").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.data.email").value("e***@example.com"))
                .andExpect(jsonPath("$.data.token").doesNotExist());

        verify("ev_happy", code).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(1));

        login("ev_happy", "password1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isEmailVerified").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void aWrongPasswordIsStillTheGenericFailureSoVerificationStatusCannotBeProbed() throws Exception {
        register(body("ev_probe")).andExpect(status().isCreated());

        login("ev_probe", "not-the-password").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nama pengguna atau kata sandi salah"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void registrationNeverVerifiesTheEmailOnItsOwn() throws Exception {
        // No path through registration may leave the account verified: the code must be entered.
        register(body("ev_noauto")).andExpect(status().isCreated());

        login("ev_noauto", "password1").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.reason").value("EMAIL_NOT_VERIFIED"));

        // sending (or resending) a code does not verify either; only entering it does
        resend("ev_noauto").andExpect(status().isOk());
        login("ev_noauto", "password1").andExpect(status().isForbidden());
    }

    @Test
    void registrationCollectsNoPhoneAndAnythingSentIsDiscarded() throws Exception {
        // An older client that still sends a phone is not rejected, but the value is not stored
        Map<String, Object> oldClient = body("ev_phone");
        oldClient.put("phone", "+628123456789");
        register(oldClient).andExpect(status().isCreated());
        EmailVerificationTestSupport.verifyEmail(mockMvc, objectMapper, email, "ev_phone", "ev_phone@example.com");

        login("ev_phone", "password1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").doesNotExist());   // none stored (null fields are omitted)
    }

    @Test
    void thePhoneIsAddedFromEditProfileAndCanBeRemoved() throws Exception {
        String token = registerAndLoginToken("ev_addphone");

        mockMvc.perform(patch("/api/users/me").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Test User\",\"email\":\"ev_addphone@example.com\",\"phone\":\"+628123456789\"}"))
                .andExpect(status().isOk());
        login("ev_addphone", "password1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("+628123456789"));

        // a malformed number is still refused
        mockMvc.perform(patch("/api/users/me").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Test User\",\"email\":\"ev_addphone@example.com\",\"phone\":\"081234\"}"))
                .andExpect(status().isBadRequest());

        // blank removes it again
        mockMvc.perform(patch("/api/users/me").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Test User\",\"email\":\"ev_addphone@example.com\",\"phone\":\"\"}"))
                .andExpect(status().isOk());
        login("ev_addphone", "password1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").doesNotExist());
    }

    // ---------- the code ----------

    @Test
    void aWrongCodeIsRejectedWithOneGenericMessage() throws Exception {
        register(body("ev_wrong")).andExpect(status().isCreated());
        String code = email.lastCodeTo("ev_wrong@example.com");

        verify("ev_wrong", wrongCodeFor(code)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Kode tidak valid atau sudah kedaluwarsa"));
        login("ev_wrong", "password1").andExpect(status().isForbidden());   // still unverified
    }

    @Test
    void anUnknownUserGetsTheSameMessageAsAWrongCode() throws Exception {
        verify("ev_nobody", "123456").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Kode tidak valid atau sudah kedaluwarsa"));
    }

    @Test
    void aCodeStopsWorkingAfterTooManyWrongGuesses() throws Exception {
        register(body("ev_lock")).andExpect(status().isCreated());
        String code = email.lastCodeTo("ev_lock@example.com");

        for (int i = 0; i < 5; i++) {
            verify("ev_lock", wrongCodeFor(code)).andExpect(status().isBadRequest());
        }
        verify("ev_lock", code).andExpect(status().isBadRequest());   // the right code is now spent
        login("ev_lock", "password1").andExpect(status().isForbidden());
    }

    @Test
    void aCodeWorksOnlyOnce() throws Exception {
        register(body("ev_once")).andExpect(status().isCreated());
        String code = email.lastCodeTo("ev_once@example.com");

        verify("ev_once", code).andExpect(status().isOk());
        verify("ev_once", code).andExpect(status().isBadRequest());
    }

    @Test
    void theCodeFieldIsValidated() throws Exception {
        verify("ev_x", "12ab56").andExpect(status().isBadRequest());
        verify("ev_x", "12345").andExpect(status().isBadRequest());
    }

    // ---------- resend ----------

    @Test
    void resendAnswersTheSameForAnyUsernameAndOnlyEmailsAnUnverifiedOne() throws Exception {
        register(body("ev_resend")).andExpect(status().isCreated());
        int before = email.totalSent();

        resend("ev_nobody_at_all").andExpect(status().isOk()).andExpect(jsonPath("$.status").value(1));
        assertThat(email.totalSent()).as("nothing sent for an unknown user").isEqualTo(before);

        // the registration's own code was just sent, so the cooldown still applies to an immediate resend
        resend("ev_resend").andExpect(status().isOk()).andExpect(jsonPath("$.status").value(1));
        assertThat(email.totalSent()).as("resend cooldown").isEqualTo(before);

        // a verified user is told the same thing and gets nothing
        EmailVerificationTestSupport.verifyEmail(mockMvc, objectMapper, email, "ev_resend", "ev_resend@example.com");
        int afterVerify = email.totalSent();
        resend("ev_resend").andExpect(status().isOk());
        assertThat(email.totalSent()).isEqualTo(afterVerify);
    }

    @Test
    void resendIsRefusedWhenEmailIsNotConfigured() throws Exception {
        email.setEnabled(false);

        resend("ev_anyone").andExpect(status().isServiceUnavailable());
    }

    // ---------- email not configured / failing ----------

    @Test
    void registrationIsRefusedAndCreatesNothingWhenEmailIsNotConfigured() throws Exception {
        email.setEnabled(false);

        register(body("ev_nomail")).andExpect(status().isServiceUnavailable());

        // nothing was created, so the same username registers normally once email works again
        email.setEnabled(true);
        register(body("ev_nomail")).andExpect(status().isCreated());
    }

    @Test
    void aMailOutageDuringRegistrationStillCreatesTheAccountAndSaysTheCodeDidNotGoOut() throws Exception {
        email.setFailing(true);

        register(body("ev_outage")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.emailSent").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("belum terkirim")));
        assertThat(email.lastCodeTo("ev_outage@example.com")).isNull();

        // mail is back: ask for a code and finish
        email.setFailing(false);
        resend("ev_outage").andExpect(status().isOk());
        EmailVerificationTestSupport.verifyEmail(mockMvc, objectMapper, email, "ev_outage", "ev_outage@example.com");
        login("ev_outage", "password1").andExpect(status().isOk());
    }

    // ---------- unique emails ----------

    @Test
    void anEmailAddressCanBelongToOnlyOneActiveUser() throws Exception {
        register(body("ev_dup1")).andExpect(status().isCreated());

        Map<String, Object> same = body("ev_dup2");
        same.put("email", "ev_dup1@example.com");
        register(same).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email sudah digunakan"));

        Map<String, Object> otherCase = body("ev_dup3");
        otherCase.put("email", "EV_DUP1@Example.COM");
        register(otherCase).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email sudah digunakan"));

        // the rejected registrations left nothing behind: those usernames are still free
        Map<String, Object> retry = body("ev_dup2");
        retry.put("email", "ev_dup2-own@example.com");
        register(retry).andExpect(status().isCreated());
    }

    @Test
    void changingProfileEmailToAnotherUsersIsRejected() throws Exception {
        String token = registerAndLoginToken("ev_prof1");
        register(body("ev_prof2")).andExpect(status().isCreated());

        patchProfile(token, "ev_prof2@example.com").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email sudah digunakan"));
    }

    // ---------- changing the address ----------

    private ResultActions patchProfile(String token, String newEmail) throws Exception {
        return mockMvc.perform(patch("/api/users/me").header("X-API-TOKEN", API_TOKEN)
                .header("Authorization", "Bearer " + token)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "Test User", "email", newEmail))));
    }

    @Test
    void changingTheEmailRequiresVerifyingTheNewAddressBeforeTheNextLogin() throws Exception {
        String token = registerAndLoginToken("ev_change");

        patchProfile(token, "ev_change-new@example.com").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Kode verifikasi")));
        String code = email.lastCodeTo("ev_change-new@example.com");
        assertThat(code).as("the NEW address was sent a code").matches("\\d{6}");

        login("ev_change", "password1").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.email").value("e***@example.com"));
        verify("ev_change", code).andExpect(status().isOk());
        login("ev_change", "password1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("ev_change-new@example.com"));
    }

    @Test
    void keepingTheSameEmailKeepsTheAccountVerified() throws Exception {
        String token = registerAndLoginToken("ev_same");

        patchProfile(token, "ev_same@example.com").andExpect(status().isOk());

        login("ev_same", "password1").andExpect(status().isOk());
    }

    // ---------- other ways to a verified address ----------

    @Test
    void resettingAForgottenPasswordAlsoVerifiesTheEmail() throws Exception {
        register(body("ev_reset")).andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/forgot-password").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("username", "ev_reset"))))
                .andExpect(status().isOk());
        String resetCode = email.lastCodeTo("ev_reset@example.com");

        mockMvc.perform(post("/api/auth/reset-password").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "username", "ev_reset", "code", resetCode, "newPassword", "BrandNew99"))))
                .andExpect(status().isOk());

        login("ev_reset", "BrandNew99").andExpect(status().isOk());   // proving the inbox was enough
    }

    @Test
    void aVerificationCodeCannotBeUsedAsAPasswordResetCode() throws Exception {
        register(body("ev_cross")).andExpect(status().isCreated());
        String verificationCode = email.lastCodeTo("ev_cross@example.com");
        mockMvc.perform(post("/api/auth/forgot-password").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("username", "ev_cross"))))
                .andExpect(status().isOk());

        // The two codes are independent: the verification digits never satisfy the reset
        String resetCode = email.lastCodeTo("ev_cross@example.com");
        String wrong = verificationCode.equals(resetCode) ? wrongCodeFor(resetCode) : verificationCode;
        mockMvc.perform(post("/api/auth/reset-password").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "username", "ev_cross", "code", wrong, "newPassword", "BrandNew99"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void subAccountsNeverNeedToVerifyAnEmail() throws Exception {
        String ownerToken = registerAndLoginToken("ev_owner");
        // team management is off on a fresh solo account; the owner turns it on before adding members
        mockMvc.perform(put("/api/account-settings/toggle").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(APPLICATION_JSON).content("{\"setting\":\"teamManagement\",\"enabled\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/users/members").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "username", "ev_staff", "password", "password1", "name", "Staff",
                                "email", "ev_staff@example.com", "phone", "+628123456780"))))
                .andExpect(status().isCreated());
        int mailsBefore = email.totalSent();

        // added by the owner: logs in right away, no code was sent
        String staffToken = objectMapper.readTree(login("ev_staff", "password1").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/data/token").asText();
        assertThat(email.totalSent()).isEqualTo(mailsBefore);

        // a legacy member stored with the flag still false (created before verification existed) also logs in
        var legacy = userRepository.findByUsernameAndDeletedAtIsNull("ev_staff").orElseThrow();
        legacy.setEmailVerified(false);
        userRepository.save(legacy);
        login("ev_staff", "password1").andExpect(status().isOk());

        // a member changing their own email keeps working: no code, no lockout
        patchProfile(staffToken, "ev_staff-new@example.com").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Profil berhasil diperbarui"));
        assertThat(email.sentTo("ev_staff-new@example.com")).isEmpty();
        login("ev_staff", "password1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("ev_staff-new@example.com"));

        // and asking for a verification code for a sub-account sends nothing
        resend("ev_staff").andExpect(status().isOk());
        assertThat(email.sentTo("ev_staff-new@example.com")).isEmpty();
        assertThat(email.sentTo("ev_staff@example.com")).isEmpty();

        // the owner, by contrast, is still gated after changing their own address
        patchProfile(ownerToken, "ev_owner-new@example.com").andExpect(status().isOk());
        login("ev_owner", "password1").andExpect(status().isForbidden());
    }
}

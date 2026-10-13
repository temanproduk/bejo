package com.bejo.web;

import com.bejo.admin.repository.AdminOtpCodeRepository;
import com.bejo.notification.InMemoryEmailSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin dashboard's API end to end: sign in with the code from the "email" (the recording
 * {@link InMemoryEmailSender}), then read the cross-business lists. The allow-list for the test
 * profile is {@code boss@example.com} and {@code second.admin@example.com}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";
    private static final String ADMIN = "boss@example.com";
    private static final String DASHBOARD_ORIGIN = "https://dashboard.test";
    private static final Pattern CODE = Pattern.compile("Kode masuk Bejo Admin Anda: (\\d+)");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InMemoryEmailSender email;
    @Autowired private AdminOtpCodeRepository codes;

    @BeforeEach
    void forgetEarlierCodes() {
        codes.deleteAll();   // otherwise the resend cooldown of one test would swallow the next one's request
    }

    @AfterEach
    void restoreMailer() {
        email.setEnabled(true);
    }

    // ---------- helpers ----------

    private ResultActions requestCode(String address) throws Exception {
        return mockMvc.perform(post("/api/admin/auth/otp/request").header("X-API-TOKEN", API_TOKEN)
                .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("email", address))));
    }

    private ResultActions verify(String address, String code) throws Exception {
        return mockMvc.perform(post("/api/admin/auth/otp/verify").header("X-API-TOKEN", API_TOKEN)
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", address, "code", code))));
    }

    private String lastCodeTo(String address) {
        List<InMemoryEmailSender.Sent> mine = email.sentTo(address);
        assertThat(mine).as("emails to " + address).isNotEmpty();
        Matcher m = CODE.matcher(mine.get(mine.size() - 1).body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private String signIn(String address) throws Exception {
        requestCode(address).andExpect(status().isOk());
        String body = verify(address, lastCodeTo(address)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private ResultActions asAdmin(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token));
    }

    private String wrongCodeFor(String realCode) {
        return realCode.equals("00000000") ? "11111111" : "00000000";
    }

    /** Registers a business + its super admin through the app's API and returns that user's JWT. */
    private String registerAndLogin(String username, String businessName) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", "password1");
        body.put("name", "Pemilik " + username);
        body.put("email", username + "@example.com");
        body.put("phone", "+628123456789");
        body.put("businessName", businessName);
        mockMvc.perform(post("/api/users/register").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        String login = mockMvc.perform(post("/api/auth/login").header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", "password1", "deviceId", "dev-" + username))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(login).path("data").path("token").asText();
    }

    private void addTransaction(String userToken, String key, String date, int amount) throws Exception {
        mockMvc.perform(post("/api/transactions").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + userToken).header("Idempotency-Key", key)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL", "transactionDate", date, "amount", amount))))
                .andExpect(status().isCreated());
    }

    // ---------- sign-in ----------

    @Test
    void anAdminSignsInWithTheEmailedEightDigitCode() throws Exception {
        requestCode(ADMIN).andExpect(status().isOk());
        String code = lastCodeTo(ADMIN);
        assertThat(code).hasSize(8);

        verify(ADMIN, code).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(43200))
                .andExpect(jsonPath("$.data.email").value(ADMIN));

        // single use
        verify(ADMIN, code).andExpect(status().isBadRequest());
    }

    @Test
    void theAllowListIgnoresCase() throws Exception {
        String token = signIn("SECOND.Admin@example.com");
        asAdmin(get("/api/admin/me"), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("second.admin@example.com"));
    }

    @Test
    void anAddressThatIsNotAnAdminGetsTheSameAnswerButNoEmailAndNoWayIn() throws Exception {
        int before = email.totalSent();
        requestCode("stranger@example.com").andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Jika alamat ini terdaftar sebagai admin, kode masuk telah dikirim"));
        assertThat(email.totalSent()).isEqualTo(before);
        verify("stranger@example.com", "12345678").andExpect(status().isBadRequest());
    }

    @Test
    void anAppUserWhoseEmailIsNotListedCannotSignIn() throws Exception {
        registerAndLogin("adm_notadmin", "Warung Bukan Admin");
        int before = email.totalSent();
        requestCode("adm_notadmin@example.com").andExpect(status().isOk());
        assertThat(email.totalSent()).isEqualTo(before);
    }

    @Test
    void aWrongCodeIsRejectedAndTheCodeLocksAfterFiveGuesses() throws Exception {
        requestCode(ADMIN).andExpect(status().isOk());
        String code = lastCodeTo(ADMIN);
        for (int i = 0; i < 5; i++) {
            verify(ADMIN, wrongCodeFor(code)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Kode tidak valid atau sudah kedaluwarsa"));
        }
        // the right code no longer works either
        verify(ADMIN, code).andExpect(status().isBadRequest());
    }

    @Test
    void aSecondRequestInsideTheCooldownSendsNothingAndANewCodeReplacesTheOld() throws Exception {
        requestCode(ADMIN).andExpect(status().isOk());
        int afterFirst = email.totalSent();
        requestCode(ADMIN).andExpect(status().isOk());
        assertThat(email.totalSent()).isEqualTo(afterFirst);

        String first = lastCodeTo(ADMIN);
        codes.findAll().forEach(c -> { c.setCreatedAt(c.getCreatedAt().minusMinutes(2)); codes.save(c); });
        requestCode(ADMIN).andExpect(status().isOk());
        String second = lastCodeTo(ADMIN);
        assertThat(email.totalSent()).isEqualTo(afterFirst + 1);
        if (!first.equals(second)) {
            verify(ADMIN, first).andExpect(status().isBadRequest());
        }
        verify(ADMIN, second).andExpect(status().isOk());
    }

    @Test
    void aMalformedCodeIsRejectedBeforeItCostsAGuess() throws Exception {
        requestCode(ADMIN).andExpect(status().isOk());
        verify(ADMIN, "123456").andExpect(status().isBadRequest());
        verify(ADMIN, lastCodeTo(ADMIN)).andExpect(status().isOk());
    }

    @Test
    void signInAnswers503WhenEmailIsNotConfigured() throws Exception {
        email.setEnabled(false);
        requestCode(ADMIN).andExpect(status().isServiceUnavailable());
    }

    // ---------- the session ----------

    @Test
    void theListsNeedAnAdminSession() throws Exception {
        mockMvc.perform(get("/api/admin/users").header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
        asAdmin(get("/api/admin/transactions"), "not-a-session").andExpect(status().isUnauthorized());

        // an app user's JWT is not an admin session, even a super admin's
        String userToken = registerAndLogin("adm_plainuser", "Warung Biasa");
        asAdmin(get("/api/admin/users"), userToken).andExpect(status().isUnauthorized());
        asAdmin(get("/api/admin/transactions"), userToken).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminSessionIsNotAnAppLogin() throws Exception {
        asAdmin(get("/api/users/me"), signIn(ADMIN)).andExpect(status().isUnauthorized());
    }

    @Test
    void theApiTokenIsStillRequired() throws Exception {
        String token = signIn(ADMIN);
        mockMvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/auth/otp/request").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signingOutEndsTheSession() throws Exception {
        String token = signIn(ADMIN);
        asAdmin(get("/api/admin/users"), token).andExpect(status().isOk());
        asAdmin(post("/api/admin/auth/logout"), token).andExpect(status().isOk());
        asAdmin(get("/api/admin/users"), token).andExpect(status().isUnauthorized());
    }

    // ---------- the lists ----------

    @Test
    void usersAreListedAcrossBusinessesWithTheirBusinessAndNoCredentials() throws Exception {
        registerAndLogin("adm_owner_a", "Kopi Senja Admintest");
        registerAndLogin("adm_owner_b", "Bengkel Maju Admintest");
        String token = signIn(ADMIN);

        asAdmin(get("/api/admin/users").param("q", "admintest"), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].username").value("adm_owner_b"))   // newest first
                .andExpect(jsonPath("$.data[0].businessName").value("Bengkel Maju Admintest"))
                .andExpect(jsonPath("$.data[0].businessRole").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data[0].email").value("adm_owner_b@example.com"))
                .andExpect(jsonPath("$.data[0].emailVerified").value(false))
                .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data[0].password").doesNotExist());

        asAdmin(get("/api/admin/users").param("q", "ADM_OWNER_A"), token)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].businessName").value("Kopi Senja Admintest"));
        asAdmin(get("/api/admin/users").param("q", "admintest").param("role", "MEMBER"), token)
                .andExpect(jsonPath("$.total").value(0));
        asAdmin(get("/api/admin/users").param("q", "admintest").param("status", "deleted"), token)
                .andExpect(jsonPath("$.total").value(0));
        asAdmin(get("/api/admin/users").param("q", "admintest").param("status", "active").param("size", "1"), token)
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.data", hasSize(1)));
        asAdmin(get("/api/admin/users").param("status", "bogus"), token).andExpect(status().isBadRequest());
    }

    @Test
    void aSearchTermsWildcardsAreTakenLiterally() throws Exception {
        registerAndLogin("adm_wild", "Toko Wildcardtest");
        String token = signIn(ADMIN);
        asAdmin(get("/api/admin/users").param("q", "wildcardtest"), token).andExpect(jsonPath("$.total").value(1));
        asAdmin(get("/api/admin/users").param("q", "wild%test"), token).andExpect(jsonPath("$.total").value(0));
        asAdmin(get("/api/admin/users").param("q", "wildcard_est"), token).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void transactionsAreListedAcrossBusinessesAndFiltered() throws Exception {
        String ownerA = registerAndLogin("adm_txn_a", "Warung Txntest Satu");
        String ownerB = registerAndLogin("adm_txn_b", "Warung Txntest Dua");
        addTransaction(ownerA, "adm-txn-1", "2026-03-10T09:00:00", 15000);
        addTransaction(ownerA, "adm-txn-2", "2026-03-12T23:59:00", 25000);
        addTransaction(ownerB, "adm-txn-3", "2026-03-11T12:00:00", 35000);
        String token = signIn(ADMIN);

        asAdmin(get("/api/admin/transactions").param("q", "txntest"), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.data[0].amount").value(25000))   // newest transaction date first
                .andExpect(jsonPath("$.data[0].businessName").value("Warung Txntest Satu"))
                .andExpect(jsonPath("$.data[0].type").value("MANUAL"))
                .andExpect(jsonPath("$.data[0].createdBy").value("adm_txn_a"))
                .andExpect(jsonPath("$.data[0].transactionId").isNotEmpty())
                .andExpect(jsonPath("$.data[1].amount").value(35000))
                .andExpect(jsonPath("$.data[1].businessName").value("Warung Txntest Dua"))
                .andExpect(jsonPath("$.data[2].amount").value(15000));

        // dateStart/dateEnd are inclusive days
        asAdmin(get("/api/admin/transactions").param("q", "txntest")
                .param("dateStart", "2026-03-11").param("dateEnd", "2026-03-12"), token)
                .andExpect(jsonPath("$.total").value(2));
        asAdmin(get("/api/admin/transactions").param("q", "txntest").param("dateEnd", "2026-03-10"), token)
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].amount").value(15000));
        asAdmin(get("/api/admin/transactions").param("q", "adm_txn_b"), token)
                .andExpect(jsonPath("$.total").value(1));
        asAdmin(get("/api/admin/transactions").param("q", "txntest").param("type", "PARSED"), token)
                .andExpect(jsonPath("$.total").value(0));
        asAdmin(get("/api/admin/transactions").param("q", "txntest").param("type", "manual"), token)
                .andExpect(jsonPath("$.total").value(3));
        asAdmin(get("/api/admin/transactions").param("q", "txntest").param("status", "FAILED"), token)
                .andExpect(jsonPath("$.total").value(0));
        asAdmin(get("/api/admin/transactions")
                .param("dateStart", "2026-03-12").param("dateEnd", "2026-03-10"), token)
                .andExpect(status().isBadRequest());
    }

    // ---------- CORS ----------

    @Test
    void theDashboardOriginMayCallTheAdminApiFromABrowser() throws Exception {
        // the preflight carries no X-API-TOKEN and must still be answered
        mockMvc.perform(options("/api/admin/users").header("Origin", DASHBOARD_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,x-api-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", DASHBOARD_ORIGIN));

        asAdmin(get("/api/admin/users").header("Origin", DASHBOARD_ORIGIN), signIn(ADMIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", DASHBOARD_ORIGIN));

        // a 401 written by a filter is readable by the dashboard too
        mockMvc.perform(get("/api/admin/users").header("Origin", DASHBOARD_ORIGIN).header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", DASHBOARD_ORIGIN));
    }

    @Test
    void otherOriginsAndOtherPathsAreNotOpened() throws Exception {
        mockMvc.perform(options("/api/admin/users").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        mockMvc.perform(get("/api/users/me").header("Origin", DASHBOARD_ORIGIN).header("X-API-TOKEN", API_TOKEN))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}

package com.bejo.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Functional tests exercising the full HTTP stack: ApiTokenFilter, Spring Security,
 * JwtAuthFilter, controllers, services, and JPA against an in-memory H2 database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {

    private static final String API_TOKEN = "test-api-token";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private com.bejo.repository.TransactionRepository transactionRepository;
    @Autowired private com.bejo.repository.UserRepository userRepository;
    @Autowired private com.bejo.repository.SessionRepository sessionRepository;
    @Autowired private com.bejo.repository.PresenceRepository presenceRepository;

    // ---------- helpers ----------

    private Map<String, Object> registerBody(String username) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", "password1");
        body.put("name", "Test User");
        body.put("email", username + "@example.com");
        body.put("phone", "+628123456789");
        body.put("businessName", "Warung " + username);
        return body;
    }

    private void register(String username) throws Exception {
        mockMvc.perform(post("/api/users/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody(username))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(1));
    }

    private String login(String username, String password, String deviceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password,
                                "deviceId", deviceId,
                                "pushToken", "fcm-" + deviceId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.at("/data/token").asText();
    }

    private String registerAndLogin(String username) throws Exception {
        register(username);
        return login(username, "password1", "device-" + username);
    }

    // Turns on team management (employees + presence) for the caller's business — a fresh solo
    // account has it off, so the team endpoints 403 until this is called.
    private void enableTeamManagement(String token) throws Exception {
        mockMvc.perform(put("/api/account-settings/toggle")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"setting\":\"teamManagement\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.teamManagementEnabled").value(true));
    }

    // Register a business super admin and turn on team management (so member/presence work).
    private String registerAdminWithTeam(String username) throws Exception {
        String token = registerAndLogin(username);
        enableTeamManagement(token);
        return token;
    }

    // Posts a MANUAL transaction with an explicit date + amount; used by summary tests.
    private void addManualTxn(String token, String idempotencyKey, String transactionDate, int amount) throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", transactionDate,
                                "amount", amount))))
                .andExpect(status().isCreated());
    }

    // ---------- API token filter ----------

    @Test
    void requestWithoutApiTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing or invalid X-API-TOKEN"));
    }

    @Test
    void requestWithWrongApiTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", "wrong-token")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthIsExemptFromApiToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorMetricsRequiresApiToken() throws Exception {
        // Non-health actuator endpoints must NOT be public
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/metrics").header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorPrometheusRequiresApiToken() throws Exception {
        // Must NOT be public — otherwise it would be exposed on Cloud Run. The local
        // Prometheus scraper authenticates via http_headers (docker/prometheus.yml).
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- auth ----------

    @Test
    void registerLoginReturnsTokenWithExpiry() throws Exception {
        register("auth_user1");

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "auth_user1",
                                "password", "password1",
                                "deviceId", "dev-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("auth_user1"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(86400))
                .andExpect(jsonPath("$.data.accountType").value("SELF_SERVICE"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("token");
    }

    @Test
    void loginWithWrongPasswordFails() throws Exception {
        register("auth_user2");

        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "auth_user2",
                                "password", "wrong-password",
                                "deviceId", "dev-1"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.message").value("Nama pengguna atau kata sandi salah"));
    }

    @Test
    void duplicateRegistrationIsRejected() throws Exception {
        register("auth_user3");

        mockMvc.perform(post("/api/users/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody("auth_user3"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username sudah digunakan"));
    }

    @Test
    void registerValidatesRequestBody() throws Exception {
        Map<String, Object> body = registerBody("auth_user4");
        body.put("password", "short");
        body.put("phone", "081234");

        mockMvc.perform(post("/api/users/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void protectedEndpointWithoutBearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutBlacklistsToken() throws Exception {
        String token = registerAndLogin("auth_user5");

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithDeviceIdClearsTheDeviceFcmToken() throws Exception {
        String token = registerAndLogin("logout_dev1");
        String deviceId = "device-logout_dev1";                 // registerAndLogin's device
        // Login stored an FCM token for the device
        assertThat(sessionRepository.findByUsernameAndDeviceId("logout_dev1", deviceId)
                .orElseThrow().getPushToken()).isNotBlank();

        mockMvc.perform(post("/api/auth/logout")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("deviceId", deviceId))))
                .andExpect(status().isOk());

        // The device's FCM token is cleared and the session is marked logged out
        var session = sessionRepository.findByUsernameAndDeviceId("logout_dev1", deviceId).orElseThrow();
        assertThat(session.getPushToken()).isNull();
        assertThat(session.getLoginStatus()).isFalse();
    }

    // ---------- transactions ----------

    @Test
    void createTransactionAndFetchHistory() throws Exception {
        String token = registerAndLogin("txn_user1");

        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "txn-key-1")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", 75000))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.transactionId").isNotEmpty());

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].amount").value(75000))
                .andExpect(jsonPath("$.data[0].acquirer").value("Cash"))
                .andExpect(jsonPath("$.data[0].merchantName").value("Warung txn_user1"));
    }

    @Test
    void transactionHistoryIsPaginated() throws Exception {
        String token = registerAndLogin("txn_page1");

        // Create 3 transactions
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/transactions")
                            .header("X-API-TOKEN", API_TOKEN)
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", "page-key-" + i)
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "type", "MANUAL",
                                    "transactionDate", "2026-07-0" + (i + 1) + "T10:00:00",
                                    "amount", 1000 * (i + 1)))))
                    .andExpect(status().isCreated());
        }

        // Default (no params) = page 1, all 3 fit on one page of 50
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.total").value(3));

        // size=2 → page 1 has 2 items, total still 3 across 2 pages
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.total").value(3));

        // page 2 of size 2 → the remaining 1 item
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void transactionSummaryReturnsTodayYesterdayAndMonthSums() throws Exception {
        String token = registerAndLogin("txn_summary1");
        // Reference "today" — use a fixed date so the buckets are deterministic
        String today = "2026-07-15";

        // 2 today (10k + 15k), 1 yesterday (7k), 1 earlier same month (5k), 1 previous month (99k)
        addManualTxn(token, "sum-1", "2026-07-15T09:00:00", 10000);
        addManualTxn(token, "sum-2", "2026-07-15T18:30:00", 15000);
        addManualTxn(token, "sum-3", "2026-07-14T12:00:00", 7000);
        addManualTxn(token, "sum-4", "2026-07-03T08:00:00", 5000);
        addManualTxn(token, "sum-5", "2026-06-30T20:00:00", 99000);

        mockMvc.perform(get("/api/transactions/summary")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("date", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(today))
                .andExpect(jsonPath("$.data.today.totalAmount").value(25000))
                .andExpect(jsonPath("$.data.today.count").value(2))
                .andExpect(jsonPath("$.data.yesterday.totalAmount").value(7000))
                .andExpect(jsonPath("$.data.yesterday.count").value(1))
                // July only: 10k + 15k + 7k + 5k = 37k across 4 txns (June's 99k excluded)
                .andExpect(jsonPath("$.data.month.totalAmount").value(37000))
                .andExpect(jsonPath("$.data.month.count").value(4));
    }

    @Test
    void transactionSummaryIsZeroWhenNoTransactions() throws Exception {
        String token = registerAndLogin("txn_summary2");

        mockMvc.perform(get("/api/transactions/summary")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("date", "2026-07-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.today.totalAmount").value(0))
                .andExpect(jsonPath("$.data.today.count").value(0))
                .andExpect(jsonPath("$.data.month.totalAmount").value(0));
    }

    @Test
    void bulkCreateSyncsOfflineTransactionsIdempotently() throws Exception {
        String token = registerAndLogin("txn_bulk1");

        Map<String, Object> item1 = Map.of("idempotencyKey", "off-1",
                "transaction", Map.of("type", "MANUAL", "transactionDate", "2026-07-01T10:00:00", "amount", 10000));
        Map<String, Object> item2 = Map.of("idempotencyKey", "off-2",
                "transaction", Map.of("type", "PARSED", "transactionDate", "2026-07-01T11:00:00", "amount", 20000, "acquirer", "AstraPay"));
        // Invalid: negative amount
        Map<String, Object> bad = Map.of("idempotencyKey", "off-3",
                "transaction", Map.of("type", "MANUAL", "transactionDate", "2026-07-01T12:00:00", "amount", -5));

        String body = objectMapper.writeValueAsString(Map.of("transactions", List.of(item1, item2, bad)));

        // First sync: 2 created, 1 failed
        mockMvc.perform(post("/api/transactions/bulk")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.created").value(2))
                .andExpect(jsonPath("$.data.failed").value(1));

        // Re-sync the same batch (client retry): the 2 good ones are now duplicates
        mockMvc.perform(post("/api/transactions/bulk")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(0))
                .andExpect(jsonPath("$.data.duplicate").value(2))
                .andExpect(jsonPath("$.data.failed").value(1));

        // Exactly the 2 valid transactions exist — no duplicates from the re-sync
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void bulkCreateEmptyListIsRejected() throws Exception {
        String token = registerAndLogin("txn_bulk2");

        mockMvc.perform(post("/api/transactions/bulk")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("transactions", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void idempotencyKeyReplayReturnsSameTransaction() throws Exception {
        String token = registerAndLogin("txn_user2");
        Map<String, Object> body = Map.of(
                "type", "MANUAL",
                "transactionDate", "2026-07-01T10:30:00",
                "amount", 10000);

        MvcResult first = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "replay-key")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "replay-key")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper.readTree(first.getResponse().getContentAsString())
                .at("/data/transactionId").asText();
        String id2 = objectMapper.readTree(second.getResponse().getContentAsString())
                .at("/data/transactionId").asText();
        assertThat(id1).isEqualTo(id2);

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void transactionWithoutIdempotencyKeyIsRejected() throws Exception {
        String token = registerAndLogin("txn_user3");

        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", 10000))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void parsedTransactionRequiresAcquirer() throws Exception {
        String token = registerAndLogin("txn_user4");

        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "parsed-key")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "PARSED",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", 10000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Metode pembayaran wajib diisi"));
    }

    @Test
    void historyDateFilterIsInclusiveOfWholeEndDay() throws Exception {
        String token = registerAndLogin("txn_user5");

        // One transaction late on 2026-06-30, one on 2026-07-01
        for (var entry : Map.of(
                "k1", "2026-06-30T23:59:59",
                "k2", "2026-07-01T08:00:00").entrySet()) {
            mockMvc.perform(post("/api/transactions")
                            .header("X-API-TOKEN", API_TOKEN)
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", entry.getKey())
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "type", "MANUAL",
                                    "transactionDate", entry.getValue(),
                                    "amount", 5000))))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("dateStart", "2026-06-30")
                        .param("dateEnd", "2026-06-30"))
                .andExpect(jsonPath("$.count").value(1));

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .param("dateStart", "2026-06-30")
                        .param("dateEnd", "2026-07-01"))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void usersOnlySeeTheirOwnTransactions() throws Exception {
        String tokenA = registerAndLogin("txn_user6a");
        String tokenB = registerAndLogin("txn_user6b");

        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", "isolation-key")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", 12345))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(jsonPath("$.count").value(0));
    }

    private String createTransaction(String token, String idempotencyKey, long amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", amount))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/transactionId").asText();
    }

    @Test
    void deletedTransactionDisappearsFromHistory() throws Exception {
        String token = registerAndLogin("txn_user7");
        String keepId = createTransaction(token, "del-key-1", 10000);
        String deleteId = createTransaction(token, "del-key-2", 20000);

        mockMvc.perform(delete("/api/transactions/" + deleteId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1));

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].transactionId").value(keepId));

        // Deleting the same transaction again is a 404 (already soft-deleted)
        mockMvc.perform(delete("/api/transactions/" + deleteId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void userCannotDeleteAnotherUsersTransaction() throws Exception {
        String tokenA = registerAndLogin("txn_user8a");
        String tokenB = registerAndLogin("txn_user8b");
        String txnId = createTransaction(tokenA, "del-key-3", 30000);

        mockMvc.perform(delete("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // Still visible to its owner
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void idempotencyKeyOfDeletedTransactionStillReplays() throws Exception {
        String token = registerAndLogin("txn_user9");
        String txnId = createTransaction(token, "del-key-4", 40000);

        mockMvc.perform(delete("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Replaying the same key returns the deleted transaction's id
        // instead of violating the unique constraint with a new insert
        MvcResult replay = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "del-key-4")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL",
                                "transactionDate", "2026-07-01T10:30:00",
                                "amount", 40000))))
                .andExpect(status().isCreated())
                .andReturn();
        String replayId = objectMapper.readTree(replay.getResponse().getContentAsString())
                .at("/data/transactionId").asText();
        assertThat(replayId).isEqualTo(txnId);

        // History stays empty — the row remains soft-deleted
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void editTransactionUpdatesOnlyProvidedFields() throws Exception {
        String token = registerAndLogin("txn_user10");
        String txnId = createTransaction(token, "edit-key-1", 50000);

        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", 60000,
                                "issuerName", "OVO"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(txnId))
                .andExpect(jsonPath("$.data.amount").value(60000))
                .andExpect(jsonPath("$.data.issuerName").value("OVO"))
                // Untouched fields survive
                .andExpect(jsonPath("$.data.acquirer").value("Cash"))
                .andExpect(jsonPath("$.data.type").value("MANUAL"));

        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].amount").value(60000));
    }

    @Test
    void editRejectsEmptyBodyAndInvalidAmount() throws Exception {
        String token = registerAndLogin("txn_user11");
        String txnId = createTransaction(token, "edit-key-2", 50000);

        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", -1))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void managedTransactionCannotBeEditedButCanBeDeleted() throws Exception {
        String token = registerAndLogin("txn_user13");

        // MANAGED rows are created by the notification flow (needs Firebase),
        // so seed one directly
        Long bizId = userRepository.findBusinessIdByUsername("txn_user13").orElseThrow();
        com.bejo.entity.Transaction t = new com.bejo.entity.Transaction();
        t.setTransactionId("TXN-MANAGED-1");
        t.setTransactionDate(java.time.LocalDateTime.of(2026, 7, 1, 10, 0));
        t.setAmount(15000L);
        t.setBusinessId(bizId);
        t.setType("MANAGED");
        t.setAcquirer("AstraPay");
        t.setStatus("SUCCESS");
        transactionRepository.save(t);

        mockMvc.perform(patch("/api/transactions/TXN-MANAGED-1")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Transaksi ini tidak dapat diubah"));

        // Delete is still allowed for MANAGED
        mockMvc.perform(delete("/api/transactions/TXN-MANAGED-1")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void editDeletedOrForeignTransactionIs404() throws Exception {
        String tokenA = registerAndLogin("txn_user12a");
        String tokenB = registerAndLogin("txn_user12b");
        String txnId = createTransaction(tokenA, "edit-key-3", 50000);

        // Another user cannot edit it
        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 1))))
                .andExpect(status().isNotFound());

        // After soft delete the owner cannot edit it either
        mockMvc.perform(delete("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("amount", 1))))
                .andExpect(status().isNotFound());
    }

    // ---------- QRIS callback (snap qr-mpm-notify) ----------

    // Builds a well-formed callback payload. (PoC: the service ignores merchantPan/refs
    // and records against the seed user regardless.)
    private String callbackBody(String merchantPan, String refNo, String partnerRef, String txnId) {
        return """
                {
                  "originalReferenceNo": "%s",
                  "originalPartnerReferenceNo": "%s",
                  "latestTransactionStatus": "00",
                  "transactionStatusDesc": "SUCCESS",
                  "customerNumber": "9360082230250015792",
                  "destinationNumber": "%s",
                  "destinationAccountName": "Asep & Testing",
                  "externalStoreId": null,
                  "amount": { "value": "100.00", "currency": "IDR" },
                  "additionalInfo": {
                    "tip": "0.00", "deviceId": "AstraPay", "channel": "POS",
                    "terminalId": "5164", "userName": "AstraPay User",
                    "merchantPan": "%s", "customerPan": "9360082230250015792",
                    "issuerName": "93600822", "transactionId": "%s"
                  }
                }
                """.formatted(refNo, partnerRef, merchantPan, merchantPan, txnId);
    }

    // PoC: the callback records against the seed user (app.callback.default-username =
    // cb_seed in test config). Register it idempotently; count its transactions by delta
    // so these tests are isolated from the shared H2 state.
    private void ensureCallbackSeedUser() throws Exception {
        if (userRepository.findByUsernameAndDeletedAtIsNull("cb_seed").isEmpty()) register("cb_seed");
    }

    private long seedTxnCount() {
        Long bizId = userRepository.findBusinessIdByUsername("cb_seed").orElseThrow();
        return transactionRepository.findByBusinessIdAndDeletedAtIsNullOrderByTransactionDateDesc(
                bizId, org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements();
    }

    @Test
    void qrisCallbackRecordsTransactionForSeedUserWithoutAnyAuth() throws Exception {
        ensureCallbackSeedUser();
        long before = seedTxnCount();
        String body = callbackBody("9360082232504524231", "XCBS6Z450S", "DEV20250826422460",
                "INV/QRM/4542BR/250826/2G4Y6CCZ");

        // No X-API-TOKEN, no JWT — external server-to-server callback
        mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                        .header("X-TIMESTAMP", "2020-12-23T08:46:11+07:00")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.id").isNumber());

        // Recorded as a MANAGED transaction against the seed user
        assertThat(seedTxnCount()).isEqualTo(before + 1);
        String token = login("cb_seed", "password1", "device-cb_seed");
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].amount").value(100))
                .andExpect(jsonPath("$.data[0].acquirer").value("AstraPay"))
                // Issuer is hard-coded to AstraPay regardless of the payload's issuerName
                .andExpect(jsonPath("$.data[0].issuerName").value("AstraPay"));
    }

    @Test
    void qrisCallbackCreatesANewTransactionEachTime() throws Exception {
        ensureCallbackSeedUser();
        long before = seedTxnCount();
        String body = callbackBody("8888000011112222", "IDEMREF01", "IDEMPART01", "INV-IDEM-01");

        // PoC: no reference to dedupe on — each callback creates a new transaction
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                            .header("X-TIMESTAMP", "2020-12-23T08:46:11+07:00")
                            .contentType(APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        assertThat(seedTxnCount()).isEqualTo(before + 2);
    }

    @Test
    void qrisCallbackWithoutAmountDefaultsToZero() throws Exception {
        ensureCallbackSeedUser();
        long before = seedTxnCount();

        // Buggy minimal payload — no amount; PoC records it with amount 0 (still 200)
        mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                        .header("X-TIMESTAMP", "2020-12-23T08:46:11+07:00")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                { "latestTransactionStatus": "00", "transactionStatusDesc": "OK" }
                                """))
                .andExpect(status().isOk());

        assertThat(seedTxnCount()).isEqualTo(before + 1);
    }

    @Test
    void qrisCallbackWorksWithoutTimestampHeader() throws Exception {
        ensureCallbackSeedUser();

        // X-TIMESTAMP is now optional (PoC) — missing header falls back to now(), still 200
        mockMvc.perform(post("/snap/v1.0/qr/qr-mpm-notify")
                        .contentType(APPLICATION_JSON)
                        .content(callbackBody("9360082232504524231", "NOTS01", "NOTS02", "INV-NOTS")))
                .andExpect(status().isOk());
    }

    // ---------- devices ----------

    @Test
    void deviceOwnerCanUpdateFcmToken() throws Exception {
        String token = registerAndLogin("dev_user1");

        mockMvc.perform(put("/api/devices/device-dev_user1/fcm-token")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"pushToken\":\"updated-fcm-token\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushToken").value("updated-fcm-token"));
    }

    @Test
    void userCannotUpdateAnotherUsersDeviceToken() throws Exception {
        registerAndLogin("dev_user2a");                     // owns device-dev_user2a
        String tokenB = registerAndLogin("dev_user2b");     // attacker

        mockMvc.perform(put("/api/devices/device-dev_user2a/fcm-token")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content("{\"pushToken\":\"hijacked-token\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- QRIS ----------

    @Test
    void qrisLifecycleSelfServiceOptionalUploadReset() throws Exception {
        String token = registerAndLogin("qris_user1");

        // Fresh self-service account: QRIS is optional, no row exists yet
        mockMvc.perform(get("/api/qris")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        // Upload a custom image — creates the QRIS row on first upload
        mockMvc.perform(post("/api/qris/upload")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"qrisImage\":\"dXBsb2FkZWQtaW1hZ2U=\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/qris")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.type").value("UPLOADED"))
                .andExpect(jsonPath("$.data.qrisImage").value("dXBsb2FkZWQtaW1hZ2U="));

        // Reset clears the uploaded image; no placeholder is generated, so the
        // managed image is an empty state (qrisImage omitted)
        mockMvc.perform(post("/api/qris/reset")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/qris")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.type").value("MANAGED"))
                .andExpect(jsonPath("$.data.qrisImage").doesNotExist());
    }

    // ---------- business members (multi-user per business) ----------

    private Map<String, Object> memberBody(String username) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", "password1");
        body.put("name", "Member " + username);
        body.put("email", username + "@example.com");
        body.put("phone", "+628123456789");
        return body;
    }

    @Test
    void teamManagementGatesMemberApisUntilEnabled() throws Exception {
        String adminToken = registerAndLogin("teamgate_admin");   // fresh solo account → team OFF

        // Adding a member is blocked while team management is off (403, gate message)
        mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("teamgate_member"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Fitur manajemen tim belum diaktifkan"));

        // Reading the member list is NOT gated
        mockMvc.perform(get("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // Enable team management → adding a member now works
        enableTeamManagement(adminToken);
        mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("teamgate_member"))))
                .andExpect(status().isCreated());
    }

    @Test
    void superAdminAddsMemberWhoSharesTheBusinessData() throws Exception {
        String adminToken = registerAdminWithTeam("biz_admin1");

        // The registering user is the SUPER_ADMIN
        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "biz_admin1", "password", "password1", "deviceId", "d1"))))
                .andExpect(jsonPath("$.data.businessRole").value("SUPER_ADMIN"));

        // Admin creates a product in the shared business
        long productId = createProductPriced(adminToken, "SHARED-SKU", 5, 10000);

        // Super admin adds a member
        mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("biz_member1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.businessRole").value("MEMBER"));

        // The member logs in and sees the SAME business's product + business name
        String memberToken = login("biz_member1", "password1", "dm1");
        mockMvc.perform(get("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sku").value("SHARED-SKU"));

        // Members list shows both users (super admin first)
        mockMvc.perform(get("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.data[0].businessRole").value("SUPER_ADMIN"));
    }

    @Test
    void memberCannotManageUsers() throws Exception {
        String adminToken = registerAdminWithTeam("biz_admin2");
        mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("biz_member2"))))
                .andExpect(status().isCreated());
        String memberToken = login("biz_member2", "password1", "dm2");

        // A plain member may not add another user (super-admin only)
        mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("biz_member2b"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Hanya super admin yang dapat mengelola pengguna"));
    }

    @Test
    void deletingMemberReleasesUsernameAndSuperAdminCannotBeDeleted() throws Exception {
        String adminToken = registerAdminWithTeam("biz_admin3");

        MvcResult added = mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("biz_member3"))))
                .andExpect(status().isCreated())
                .andReturn();
        long memberId = objectMapper.readTree(added.getResponse().getContentAsString()).at("/data/id").asLong();
        long adminId = userRepository.findByUsernameAndDeletedAtIsNull("biz_admin3").orElseThrow().getId();

        // Super admin cannot be deleted
        mockMvc.perform(delete("/api/users/members/" + adminId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Super admin tidak dapat dihapus"));

        // Deleting the member soft-deletes it and releases the username
        mockMvc.perform(delete("/api/users/members/" + memberId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // The deleted user can no longer log in...
        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "biz_member3", "password", "password1", "deviceId", "dm3"))))
                .andExpect(status().isBadRequest());

        // ...and the released username can be registered fresh (as a new business)
        mockMvc.perform(post("/api/users/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerBody("biz_member3"))))
                .andExpect(status().isCreated());
    }

    @Test
    void memberEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerWithBusinessSizeIndustryAndStorePhotoSurfacesOnLogin() throws Exception {
        String photo = java.util.Base64.getEncoder().encodeToString("store".getBytes());
        Map<String, Object> body = new HashMap<>(registerBody("biz_class1"));
        body.put("size", "Menengah");
        body.put("industry", "Otomotif");
        body.put("storePhoto", photo);
        mockMvc.perform(post("/api/users/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        // On login they come back; NOT on /me (immutable → app caches from login)
        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "biz_class1", "password", "password1", "deviceId", "d-bc1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value("Menengah"))
                .andExpect(jsonPath("$.data.industry").value("Otomotif"))
                .andExpect(jsonPath("$.data.storePhoto").value(photo));

        String token = login("biz_class1", "password1", "d-bc1b");
        mockMvc.perform(get("/api/users/me")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").doesNotExist())
                .andExpect(jsonPath("$.data.industry").doesNotExist())
                .andExpect(jsonPath("$.data.storePhoto").doesNotExist());
    }

    @Test
    void registerDefaultsSizeAndIndustryWhenOmitted() throws Exception {
        register("biz_class2");  // no size/industry in the body
        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "biz_class2", "password", "password1", "deviceId", "d-bc2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value("Mikro"))
                .andExpect(jsonPath("$.data.industry").value("Makanan & Minuman"));
    }

    @Test
    void registerRejectsInvalidBusinessSize() throws Exception {
        Map<String, Object> body = new HashMap<>(registerBody("biz_class3"));
        body.put("size", "Raksasa");
        mockMvc.perform(post("/api/users/register")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("size tidak valid")));
    }

    @Test
    void onlySuperAdminCanChangeBusinessSettingsPictureAndQris() throws Exception {
        String adminToken = registerAdminWithTeam("biz_admin6");
        mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("biz_member6"))))
                .andExpect(status().isCreated());
        String memberToken = login("biz_member6", "password1", "dm6");

        // A member may READ everything
        mockMvc.perform(get("/api/account-settings")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());

        // ...but every MUTATION to app config / picture / QRIS is 403 for a member
        String forbidden = "Hanya super admin yang dapat mengubah pengaturan bisnis";

        mockMvc.perform(put("/api/account-settings/toggle")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + memberToken)
                        .contentType(APPLICATION_JSON).content("{\"setting\":\"inventory\",\"enabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(forbidden));

        mockMvc.perform(put("/api/account-settings/keywords")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + memberToken)
                        .contentType(APPLICATION_JSON).content("{\"excludeKeywords\":[\"promo\"]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/account-settings/monitored-apps")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + memberToken)
                        .contentType(APPLICATION_JSON).content("{\"apps\":{\"id.dana\":\"dana\"}}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + memberToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("profilePicture",
                                java.util.Base64.getEncoder().encodeToString("x".getBytes())))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/qris/upload")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + memberToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("qrisImage",
                                java.util.Base64.getEncoder().encodeToString("qr".getBytes())))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/qris/reset")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isForbidden());

        // The super admin can still do all of them
        mockMvc.perform(put("/api/account-settings/toggle")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON).content("{\"setting\":\"inventory\",\"enabled\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("profilePicture",
                                java.util.Base64.getEncoder().encodeToString("x".getBytes())))))
                .andExpect(status().isOk());
    }

    @Test
    void superAdminUpdatesMember() throws Exception {
        String adminToken = registerAdminWithTeam("biz_admin4");
        MvcResult added = mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("biz_member4"))))
                .andExpect(status().isCreated())
                .andReturn();
        long memberId = objectMapper.readTree(added.getResponse().getContentAsString()).at("/data/id").asLong();

        mockMvc.perform(patch("/api/users/members/" + memberId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Member Four Updated", "email", "member4.new@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Member Four Updated"))
                .andExpect(jsonPath("$.data.email").value("member4.new@example.com"))
                .andExpect(jsonPath("$.data.businessRole").value("MEMBER"));

        // The updated member can log in with the new password
        mockMvc.perform(patch("/api/users/members/" + memberId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "NewSecret1"))))
                .andExpect(status().isOk());
        login("biz_member4", "NewSecret1", "dm4");
    }

    @Test
    void selfServiceAccountUpgradesToManaged() throws Exception {
        String token = registerAndLogin("upg_user1");

        // Both merchantPan and nmid are required
        mockMvc.perform(post("/api/users/me/upgrade").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"merchantPan\":\"MPAN-UPG1\"}"))
                .andExpect(status().isBadRequest());

        // Valid upgrade → 200, NMID now set on the business
        mockMvc.perform(post("/api/users/me/upgrade").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"merchantPan\":\"MPAN-UPG1\",\"nmid\":\"ID-UPG1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nmid").value("ID-UPG1"));

        // Upgrading again is rejected — the account is already managed
        mockMvc.perform(post("/api/users/me/upgrade").header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token).contentType(APPLICATION_JSON)
                        .content("{\"merchantPan\":\"MPAN-UPG1\",\"nmid\":\"ID-UPG1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Akun sudah dikelola Bejo"));
    }

    @Test
    void getMeReturnsOwnAccountBundle() throws Exception {
        String token = registerAndLogin("me_user1");

        mockMvc.perform(get("/api/users/me")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.username").value("me_user1"))
                .andExpect(jsonPath("$.data.businessRole").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.businessName").value("Warung me_user1"))
                // app settings are bundled (defaults when never synced)
                .andExpect(jsonPath("$.data.accountSettings.blacklistEnabled").value(true))
                .andExpect(jsonPath("$.data.accountSettings.receiveAds").value(false));
    }

    @Test
    void loginBundlesAccountSettingsAndIdAndPicture() throws Exception {
        register("me_user2");
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "me_user2", "password", "password1", "deviceId", "d-me2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andExpect(jsonPath("$.data.businessRole").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.data.accountSettings.inventoryEnabled").value(false))
                .andExpect(jsonPath("$.data.accountSettings.receiveAds").value(false))
                // no business picture set yet → omitted (null, NON_NULL)
                .andExpect(jsonPath("$.data.profilePicture").doesNotExist())
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).contains("accountSettings");
    }

    @Test
    void deletedMemberExistingTokenIsRejected() throws Exception {
        String adminToken = registerAdminWithTeam("biz_admin5");
        MvcResult added = mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("biz_member5"))))
                .andExpect(status().isCreated())
                .andReturn();
        long memberId = objectMapper.readTree(added.getResponse().getContentAsString()).at("/data/id").asLong();

        // Member logs in and holds a valid token
        String memberToken = login("biz_member5", "password1", "dm5");
        mockMvc.perform(get("/api/users/me")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isOk());

        // Super admin deletes the member
        mockMvc.perform(delete("/api/users/members/" + memberId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // The member's still-signed, still-unexpired token is now rejected
        mockMvc.perform(get("/api/users/me")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void qrisUploadRejectsInvalidBase64() throws Exception {
        String token = registerAndLogin("qris_user2");

        mockMvc.perform(post("/api/qris/upload")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"qrisImage\":\"@@ not base64 @@\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- user profile ----------

    @Test
    void profileBankAndBusinessUpdateAndReset() throws Exception {
        String token = registerAndLogin("prof_user1");

        mockMvc.perform(patch("/api/users/me")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Updated Name",
                                "email", "updated@example.com",
                                "phone", "+628999999999"))))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/users/me/bank")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankName", "Mandiri",
                                "bankNumber", "9876543210",
                                "bankAccountName", "Updated Name"))))
                .andExpect(status().isOk());

        // Bank reset restores the (empty) initial values from registration
        mockMvc.perform(post("/api/users/me/bank/reset")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/users/me/business")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "businessName", "Toko Baru"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/users/me/business/reset")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Business name restored in the next login response
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "prof_user1",
                                "password", "password1",
                                "deviceId", "dev-x"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(json.at("/data/businessName").asText()).isEqualTo("Warung prof_user1");
    }

    @Test
    void changePasswordFlow() throws Exception {
        String token = registerAndLogin("pass_user1");

        mockMvc.perform(patch("/api/users/me/password")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "wrong-old",
                                "newPassword", "newpass123"))))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/users/me/password")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "password1",
                                "newPassword", "newpass123"))))
                .andExpect(status().isOk());

        // Old password no longer works, new one does
        mockMvc.perform(post("/api/auth/login")
                        .header("X-API-TOKEN", API_TOKEN)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "pass_user1",
                                "password", "password1",
                                "deviceId", "dev-1"))))
                .andExpect(status().isBadRequest());

        login("pass_user1", "newpass123", "dev-1");
    }

    @Test
    void profilePictureGetSetFlow() throws Exception {
        String token = registerAndLogin("pic_user1");

        // No picture yet — the field is present and explicitly null (no placeholder)
        mockMvc.perform(get("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profilePicture").value(org.hamcrest.Matchers.nullValue()));

        // Set a picture (valid base64)
        String base64 = java.util.Base64.getEncoder().encodeToString("fake-image-bytes".getBytes());
        mockMvc.perform(put("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("profilePicture", base64))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profilePicture").value(base64));

        // GET returns it
        mockMvc.perform(get("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.profilePicture").value(base64));

        // DELETE clears it
        mockMvc.perform(delete("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1));

        // GET is null again
        mockMvc.perform(get("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.profilePicture").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void profilePictureRejectsInvalidBase64() throws Exception {
        String token = registerAndLogin("pic_user2");

        mockMvc.perform(put("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("profilePicture", "@@ not base64 @@"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void profilePictureRequiresJwt() throws Exception {
        mockMvc.perform(get("/api/business/picture")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    // ---------- notifications ----------

    @Test
    void notificationWithoutFirebaseConfiguredReturns503() throws Exception {
        String token = registerAndLogin("notif_user1");

        mockMvc.perform(post("/api/notifications/test")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "deviceId", "device-notif_user1",
                                "title", "Pembayaran diterima",
                                "body", "Rp 10.000",
                                "data", Map.of(
                                        "transactionId", "TXN-NOTIF-1",
                                        "transactionDate", "2026-07-01T10:00:00",
                                        "amount", 10000,
                                        "issuerName", "GoPay")))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void broadcastToUserWithoutFirebaseConfiguredReturns503() throws Exception {
        // POST /api/notifications/user broadcasts to all the caller's devices (no deviceId)
        String token = registerAndLogin("notif_user2");

        mockMvc.perform(post("/api/notifications/user")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Pembayaran diterima",
                                "body", "Rp 10.000",
                                "data", Map.of(
                                        "transactionId", "TXN-NOTIF-2",
                                        "transactionDate", "2026-07-01T10:00:00",
                                        "amount", 10000,
                                        "issuerName", "GoPay")))))
                .andExpect(status().isServiceUnavailable());
    }

    // ---------- products & categories ----------

    private long createCategory(String token, String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/categories")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/id").asLong();
    }

    @Test
    void productCategoryAndPhotoLifecycle() throws Exception {
        String token = registerAndLogin("prod_user1");
        long catId = createCategory(token, "Minuman");

        // Create a product in that category, with a small base64 photo
        String photo = java.util.Base64.getEncoder().encodeToString("img".getBytes());
        Map<String, Object> body = new HashMap<>();
        body.put("sku", "KOPI-01");
        body.put("name", "Kopi Susu");
        body.put("shortDescription", "Kopi + susu segar");
        body.put("categoryId", catId);
        body.put("price", 18000);
        body.put("costPrice", 10000);
        body.put("stock", 25);
        body.put("photo", photo);

        MvcResult created = mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryName").value("Minuman"))
                .andExpect(jsonPath("$.data.shortDescription").value("Kopi + susu segar"))
                .andExpect(jsonPath("$.data.stock").value(25))
                .andReturn();
        long productId = objectMapper.readTree(created.getResponse().getContentAsString()).at("/data/id").asLong();

        // List omits the photo to keep payloads small
        mockMvc.perform(get("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].photo").doesNotExist())
                .andExpect(jsonPath("$.data[0].categoryName").value("Minuman"));

        // Detail includes the photo
        mockMvc.perform(get("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.photo").value(photo));

        // Filter by category
        mockMvc.perform(get("/api/products?categoryId=" + catId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(1));

        // Update stock + short description (inventory + editable description via PATCH)
        mockMvc.perform(patch("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stock", 40, "shortDescription", "Promo!"))))
                .andExpect(jsonPath("$.data.stock").value(40))
                .andExpect(jsonPath("$.data.shortDescription").value("Promo!"));

        // Deleting the category un-categorizes the product (not deletes it)
        mockMvc.perform(delete("/api/categories/" + catId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.categoryId").doesNotExist())
                .andExpect(jsonPath("$.data.categoryName").doesNotExist());

        // Delete the product
        mockMvc.perform(delete("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void productDuplicateSkuRejected() throws Exception {
        String token = registerAndLogin("prod_user2");
        Map<String, Object> body = Map.of("sku", "DUP-1", "name", "A", "price", 1000);

        mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void productWithForeignCategoryRejected() throws Exception {
        String tokenA = registerAndLogin("prod_user3a");
        String tokenB = registerAndLogin("prod_user3b");
        long catA = createCategory(tokenA, "Makanan");

        // User B cannot attach user A's category
        mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sku", "X-1", "name", "X", "price", 1000, "categoryId", catA))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void productRequiresJwt() throws Exception {
        mockMvc.perform(get("/api/products")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bulkStockAdjustmentSetsCountsAndReportsVariance() throws Exception {
        String token = registerAndLogin("opname_user1");
        long p1 = createProduct(token, "OP-1", 10, null);
        long p2 = createProduct(token, "OP-2", 5, null);

        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("productId", p1, "stock", 8),    // counted 8  (-2)
                Map.of("productId", p2, "stock", 12)))); // counted 12 (+7)

        mockMvc.perform(post("/api/products/stock-adjustments")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].previousStock").value(10))
                .andExpect(jsonPath("$.data.items[0].newStock").value(8))
                .andExpect(jsonPath("$.data.items[0].difference").value(-2))
                .andExpect(jsonPath("$.data.items[1].difference").value(7));

        // Stock actually updated
        assertThat(stockOf(token, p1)).isEqualTo(8);
        assertThat(stockOf(token, p2)).isEqualTo(12);
    }

    @Test
    void bulkStockAdjustmentUnknownProductRejectedAtomically() throws Exception {
        String token = registerAndLogin("opname_user2");
        long p1 = createProduct(token, "OP-1", 10, null);

        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("productId", p1, "stock", 99),
                Map.of("productId", 888888, "stock", 3))));

        mockMvc.perform(post("/api/products/stock-adjustments")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // Atomic: the valid product's stock is unchanged
        assertThat(stockOf(token, p1)).isEqualTo(10);
    }

    // ---------- purchases (restock) ----------

    private long createProduct(String token, String sku, int stock, Integer costPrice) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("sku", sku);
        body.put("name", "Product " + sku);
        body.put("stock", stock);
        if (costPrice != null) body.put("costPrice", costPrice);
        MvcResult r = mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/id").asLong();
    }

    private int stockOf(String token, long productId) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/products/" + productId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/stock").asInt();
    }

    @Test
    void purchaseRaisesStockOfAllProductsAndPersists() throws Exception {
        String token = registerAndLogin("buy_user1");
        long p1 = createProduct(token, "P1", 10, 5000);
        long p2 = createProduct(token, "P2", 3, 8000);

        String body = objectMapper.writeValueAsString(Map.of(
                "note", "restock pagi",
                "items", List.of(
                        Map.of("productId", p1, "quantity", 20),                  // uses costPrice 5000
                        Map.of("productId", p2, "quantity", 5, "unitPrice", 9000)))); // override

        MvcResult created = mockMvc.perform(post("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("PURCHASE"))
                .andExpect(jsonPath("$.data.totalAmount").value(145000)) // 20*5000 + 5*9000
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].previousStock").value(10))
                .andExpect(jsonPath("$.data.items[0].newStock").value(30))
                .andExpect(jsonPath("$.data.items[0].quantityChange").value(20))
                .andReturn();
        long mutationId = objectMapper.readTree(created.getResponse().getContentAsString()).at("/data/id").asLong();

        // Both products' stock rose
        assertThat(stockOf(token, p1)).isEqualTo(30);
        assertThat(stockOf(token, p2)).isEqualTo(8);

        // History: list includes items (product summary + before/after stock)
        mockMvc.perform(get("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].items.length()").value(2))
                .andExpect(jsonPath("$.data[0].items[0].name").value("Product P1"))
                .andExpect(jsonPath("$.data[0].items[0].newStock").value(30));

        mockMvc.perform(get("/api/stock-mutations/" + mutationId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].sku").value("P1"));
    }

    @Test
    void purchaseWithUnknownProductRejectedAndNoStockChange() throws Exception {
        String token = registerAndLogin("buy_user2");
        long p1 = createProduct(token, "P1", 10, 5000);

        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("productId", p1, "quantity", 5),
                Map.of("productId", 999999, "quantity", 2))));

        mockMvc.perform(post("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // Atomic: the valid product's stock is unchanged
        assertThat(stockOf(token, p1)).isEqualTo(10);
    }

    @Test
    void purchaseEmptyItemsRejected() throws Exception {
        String token = registerAndLogin("buy_user3");

        mockMvc.perform(post("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stockMutationHistoryCapturesPurchaseAdjustmentAndUpdate() throws Exception {
        String token = registerAndLogin("mut_user1");
        long p1 = createProduct(token, "M1", 10, 5000);

        // 1) Restock (PURCHASE): +5
        mockMvc.perform(post("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", List.of(
                                Map.of("productId", p1, "quantity", 5))))))
                .andExpect(status().isCreated());

        // 2) Stock opname (ADJUSTMENT): set to 20, with an optional note
        mockMvc.perform(post("/api/products/stock-adjustments")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "note", "opname sore",
                                "items", List.of(Map.of("productId", p1, "stock", 20))))))
                .andExpect(status().isOk());

        // 3) Product edit changing stock (UPDATE): set to 25
        mockMvc.perform(patch("/api/products/" + p1)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stock", 25))))
                .andExpect(status().isOk());

        // 4) A non-stock edit (name only) must NOT create a mutation
        mockMvc.perform(patch("/api/products/" + p1)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Renamed"))))
                .andExpect(status().isOk());

        // History has exactly the 3 stock-changing mutations, newest first
        mockMvc.perform(get("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.data[0].type").value("UPDATE"))
                .andExpect(jsonPath("$.data[1].type").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.data[1].note").value("opname sore"))
                .andExpect(jsonPath("$.data[2].type").value("PURCHASE"));
    }

    // ---------- app settings ----------

    @Test
    void accountSettingsDefaultsThenUpdate() throws Exception {
        String token = registerAndLogin("appset_user1");

        // Defaults when nothing has been synced yet
        mockMvc.perform(get("/api/account-settings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blacklistEnabled").value(true))
                .andExpect(jsonPath("$.data.whitelistEnabled").value(false))
                .andExpect(jsonPath("$.data.qrisWithProfilePicture").value(false))
                .andExpect(jsonPath("$.data.inventoryEnabled").value(false))
                .andExpect(jsonPath("$.data.receiveAds").value(false))
                .andExpect(jsonPath("$.data.monitoredApps").isEmpty())
                .andExpect(jsonPath("$.data.includeKeywords").isEmpty());

        // Save monitored apps (issuer is trimmed + lowercased)
        mockMvc.perform(put("/api/account-settings/monitored-apps")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"apps\":{\"id.dana\":\"DANA\",\"com.gojek.app\":\"gopay\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monitoredApps['id.dana']").value("dana"));

        // Save keywords + toggle (Promo/promo dedupe to one entry)
        mockMvc.perform(put("/api/account-settings/keywords")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"excludeKeywords\":[\"Promo\",\"promo\",\"diskon\"],\"whitelistEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.excludeKeywords[0]").value("promo"))
                .andExpect(jsonPath("$.data.excludeKeywords[1]").value("diskon"))
                .andExpect(jsonPath("$.data.whitelistEnabled").value(true));

        // GET reflects the saved state; untouched blacklist toggle stays at its default
        mockMvc.perform(get("/api/account-settings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.monitoredApps['com.gojek.app']").value("gopay"))
                .andExpect(jsonPath("$.data.excludeKeywords[0]").value("promo"))
                .andExpect(jsonPath("$.data.blacklistEnabled").value(true));
    }

    @Test
    void accountSettingsConfigTogglesFlow() throws Exception {
        String token = registerAndLogin("appset_cfg1");

        // Generic toggle: enable "QRIS with profile picture"
        mockMvc.perform(put("/api/account-settings/toggle")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"setting\":\"qrisWithProfilePicture\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.qrisWithProfilePicture").value(true))
                .andExpect(jsonPath("$.data.inventoryEnabled").value(false));

        // Enable inventory management
        mockMvc.perform(put("/api/account-settings/toggle")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"setting\":\"inventory\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventoryEnabled").value(true));

        // Opt in to receive ads (default was false)
        mockMvc.perform(put("/api/account-settings/toggle")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"setting\":\"receiveAds\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiveAds").value(true));

        // GET reflects the flags
        mockMvc.perform(get("/api/account-settings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.qrisWithProfilePicture").value(true))
                .andExpect(jsonPath("$.data.inventoryEnabled").value(true));

        // An unknown setting is rejected
        mockMvc.perform(put("/api/account-settings/toggle")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"setting\":\"bogus\",\"enabled\":true}"))
                .andExpect(status().isBadRequest());

        // Missing 'enabled' is rejected
        mockMvc.perform(put("/api/account-settings/toggle")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"setting\":\"inventory\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accountSettingsRejectInvalidPackage() throws Exception {
        String token = registerAndLogin("appset_user2");

        mockMvc.perform(put("/api/account-settings/monitored-apps")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"apps\":{\"bad package!\":\"x\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void accountSettingsRequireJwt() throws Exception {
        mockMvc.perform(get("/api/account-settings")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    // ---------- transactions with product line items ----------

    private long createProductPriced(String token, String sku, int stock, long price) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/products")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sku", sku, "name", "Product " + sku, "stock", stock, "price", price))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/id").asLong();
    }

    @Test
    void manualTransactionWithItemsReducesStockAndExposesLineItems() throws Exception {
        String token = registerAndLogin("txn_items1");
        long coffee = createProductPriced(token, "COF", 10, 15000);
        long tea = createProductPriced(token, "TEA", 5, 8000);

        String body = objectMapper.writeValueAsString(Map.of(
                "type", "MANUAL",
                "transactionDate", "2026-07-01T10:30:00",
                "amount", 61000,
                "items", List.of(
                        Map.of("productId", coffee, "quantity", 3),
                        Map.of("productId", tea, "quantity", 2))));
        MvcResult created = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "items-key-1")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String txnId = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/transactionId").asText();

        // Stock reduced by the sold quantities
        assertThat(stockOf(token, coffee)).isEqualTo(7);
        assertThat(stockOf(token, tea)).isEqualTo(3);

        // The single-transaction detail exposes the sold line items with snapshot prices
        mockMvc.perform(get("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].name").value("Product COF"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(15000))
                .andExpect(jsonPath("$.data.items[0].subtotal").value(45000));

        // The history list omits the line items (keeps the payload small)
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].items").doesNotExist());

        // A SALE stock mutation is recorded so the stock ledger stays complete
        mockMvc.perform(get("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].type").value("SALE"))
                .andExpect(jsonPath("$.data[0].items.length()").value(2));
    }

    @Test
    void editTransactionItemsReconcilesStockAndAmount() throws Exception {
        String token = registerAndLogin("txn_edit1");
        long coffee = createProductPriced(token, "COF", 10, 15000);
        long tea = createProductPriced(token, "TEA", 5, 8000);

        // Create: coffee×3, tea×2 → stock 7 / 3
        MvcResult created = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "edit-items-1").contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL", "transactionDate", "2026-07-01T10:30:00", "amount", 61000,
                                "items", List.of(Map.of("productId", coffee, "quantity", 3),
                                                 Map.of("productId", tea, "quantity", 2))))))
                .andExpect(status().isCreated()).andReturn();
        String txnId = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/transactionId").asText();

        // Edit: raise coffee to 5, drop tea → coffee 7→5, tea 3→5 (returned), amount re-derived 75000
        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", coffee, "quantity", 5))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(75000))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].quantity").value(5));
        assertThat(stockOf(token, coffee)).isEqualTo(5);
        assertThat(stockOf(token, tea)).isEqualTo(5);

        // Clearing items ([]) returns coffee's stock and removes the line items
        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").doesNotExist());
        assertThat(stockOf(token, coffee)).isEqualTo(10);
        mockMvc.perform(get("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items").doesNotExist());
    }

    @Test
    void editTransactionItemsRejectsInsufficientStockAndLeavesEverythingUnchanged() throws Exception {
        String token = registerAndLogin("txn_edit2");
        long coffee = createProductPriced(token, "COF", 3, 15000);
        MvcResult created = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "edit-items-2").contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL", "transactionDate", "2026-07-01T10:30:00", "amount", 30000,
                                "items", List.of(Map.of("productId", coffee, "quantity", 2))))))
                .andExpect(status().isCreated()).andReturn();
        String txnId = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/transactionId").asText();

        // stock is now 1 (3−2); editing to qty 5 needs +3 more but only 1 is available → 400
        mockMvc.perform(patch("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "items", List.of(Map.of("productId", coffee, "quantity", 5))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("Stok tidak cukup")));

        // Nothing changed — stock and the original line item are intact
        assertThat(stockOf(token, coffee)).isEqualTo(1);
        mockMvc.perform(get("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));
    }

    @Test
    void deleteTransactionWithItemsReturnsStock() throws Exception {
        String token = registerAndLogin("txn_del1");
        long coffee = createProductPriced(token, "COF", 10, 15000);

        MvcResult created = mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "del-items-1").contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "type", "MANUAL", "transactionDate", "2026-07-01T10:30:00", "amount", 45000,
                                "items", List.of(Map.of("productId", coffee, "quantity", 3))))))
                .andExpect(status().isCreated()).andReturn();
        String txnId = objectMapper.readTree(created.getResponse().getContentAsString())
                .at("/data/transactionId").asText();
        assertThat(stockOf(token, coffee)).isEqualTo(7);   // 3 sold

        // Deleting the sale returns its stock
        mockMvc.perform(delete("/api/transactions/" + txnId)
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(stockOf(token, coffee)).isEqualTo(10);  // 3 returned

        // ...and it's gone from history, with a SALE reversal mutation recorded
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(0));
        mockMvc.perform(get("/api/stock-mutations")
                        .header("X-API-TOKEN", API_TOKEN).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[0].type").value("SALE"))
                .andExpect(jsonPath("$.data[0].items[0].quantityChange").value(3));
    }

    @Test
    void manualTransactionWithInsufficientStockIsRejectedAndStockUnchanged() throws Exception {
        String token = registerAndLogin("txn_items2");
        long coffee = createProductPriced(token, "COF", 2, 15000);

        String body = objectMapper.writeValueAsString(Map.of(
                "type", "MANUAL",
                "transactionDate", "2026-07-01T10:30:00",
                "amount", 75000,
                "items", List.of(Map.of("productId", coffee, "quantity", 5))));
        mockMvc.perform(post("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "items-key-2")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // Nothing changed — no stock deducted, no transaction recorded
        assertThat(stockOf(token, coffee)).isEqualTo(2);
        mockMvc.perform(get("/api/transactions")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.count").value(0));
    }

    // ---------- employee daily presence ----------

    // Registers a business, adds one member to it, and returns {adminToken, memberToken, memberId}.
    private Map<String, String> businessWithMember(String adminUsername, String memberUsername) throws Exception {
        String adminToken = registerAndLogin(adminUsername);
        enableTeamManagement(adminToken);   // team features are gated on this
        MvcResult result = mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody(memberUsername))))
                .andExpect(status().isCreated())
                .andReturn();
        String memberId = objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/id").asText();
        String memberToken = login(memberUsername, "password1", "d-" + memberUsername);
        return Map.of("adminToken", adminToken, "memberToken", memberToken, "memberId", memberId);
    }

    private void clock(String token, String path, String clockDatetime) throws Exception {
        mockMvc.perform(post("/api/presences/" + path)
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.2088,
                                "longitude", 106.8456,
                                "clockDatetime", clockDatetime))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(1));
    }

    @Test
    void memberClocksInAndOutAndBothSidesSeeIt() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin1", "pres_member1");

        clock(biz.get("memberToken"), "clock-in", "2026-03-10T08:01:00");
        clock(biz.get("memberToken"), "clock-out", "2026-03-10T17:04:00");

        // The member sees their own two entries
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.data[0].type").value("CLOCK_OUT"))   // newest first
                .andExpect(jsonPath("$.data[0].presenceDate").value("2026-03-10"))
                .andExpect(jsonPath("$.data[1].type").value("CLOCK_IN"))
                .andExpect(jsonPath("$.data[1].latitude").value(-6.2088))
                .andExpect(jsonPath("$.data[1].username").value("pres_member1"));

        // The super admin sees the whole business, filtered to that member + month
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .param("userId", biz.get("memberId"))
                        .param("year", "2026")
                        .param("month", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        // A different month is empty
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .param("year", "2026")
                        .param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void clockingOutRequiresThatDaysClockIn() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin17", "pres_member17");

        // You can't leave a shift you never started
        mockMvc.perform(post("/api/presences/clock-out")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.2, "longitude", 106.8,
                                "clockDatetime", "2026-04-10T17:00:00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Kamu belum absen masuk hari ini"));

        // Clock in on the 10th → that day's clock-out is now allowed
        clock(biz.get("memberToken"), "clock-in", "2026-04-10T08:00:00");
        clock(biz.get("memberToken"), "clock-out", "2026-04-10T17:00:00");

        // …but the NEXT day's clock-out still needs its own clock-in — yesterday's doesn't carry over
        mockMvc.perform(post("/api/presences/clock-out")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.2, "longitude", 106.8,
                                "clockDatetime", "2026-04-11T17:00:00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void aPingExpires24HoursAfterItWasSent() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin18", "pres_member18");
        MvcResult ping = mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(status().isCreated())
                .andReturn();
        long pingId = objectMapper.readTree(ping.getResponse().getContentAsString())
                .at("/data/presences/0/id").asLong();

        // Still answerable 10 hours on — the window is a rolling 24h, not the calendar date
        com.bejo.entity.Presence entry = presenceRepository.findById(pingId).orElseThrow();
        entry.setClockDatetime(LocalDateTime.now().minusHours(10));
        presenceRepository.save(entry);

        mockMvc.perform(post("/api/presences/" + pingId + "/respond")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.2, "longitude", 106.8))))
                .andExpect(status().isOk());

        // A second ping, this one older than 24h, has expired
        entry = presenceRepository.findById(pingId).orElseThrow();
        entry.setResponseDatetime(null);
        entry.setClockDatetime(LocalDateTime.now().minusHours(25));
        presenceRepository.save(entry);

        mockMvc.perform(post("/api/presences/" + pingId + "/respond")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.2, "longitude", 106.8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Ping sudah kedaluwarsa (maksimal 24 jam)"));
    }

    @Test
    void superAdminCannotClockIn() throws Exception {
        String adminToken = registerAdminWithTeam("pres_admin2");

        mockMvc.perform(post("/api/presences/clock-in")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.2, "longitude", 106.8,
                                "clockDatetime", "2026-03-10T08:00:00"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void clockInRequiresLocationAndDatetime() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin3", "pres_member3");

        mockMvc.perform(post("/api/presences/clock-in")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("note", "lupa lokasi"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void superAdminPingsAndTheMemberResponds() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin4", "pres_member4");

        MvcResult ping = mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pinged").value(1))            // one entry per member
                .andExpect(jsonPath("$.data.totalMembers").value(1))
                .andExpect(jsonPath("$.data.presences[0].type").value("PING"))
                .andExpect(jsonPath("$.data.presences[0].username").value("pres_member4"))
                .andExpect(jsonPath("$.data.presences[0].note").doesNotExist())  // the note is the member's to write
                .andReturn();
        String pingId = objectMapper.readTree(ping.getResponse().getContentAsString()).at("/data/presences/0/id").asText();

        // The member taps it and replies with their location + a note
        mockMvc.perform(post("/api/presences/" + pingId + "/respond")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.1751,
                                "longitude", 106.8650,
                                "note", "Lagi antar pesanan"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseDatetime").isNotEmpty())
                .andExpect(jsonPath("$.data.latitude").value(-6.1751))
                .andExpect(jsonPath("$.data.note").value("Lagi antar pesanan"));

        // Responding twice is rejected
        mockMvc.perform(post("/api/presences/" + pingId + "/respond")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.1751, "longitude", 106.8650))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // The super admin reads the reply — response time, WHERE they were, and the note
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].type").value("PING"))
                .andExpect(jsonPath("$.data[0].responseDatetime").isNotEmpty())
                .andExpect(jsonPath("$.data[0].latitude").value(-6.1751))
                .andExpect(jsonPath("$.data[0].longitude").value(106.8650))
                .andExpect(jsonPath("$.data[0].note").value("Lagi antar pesanan"));
    }

    @Test
    void aMemberCannotBePingedTwiceWithinAnHour() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin15", "pres_member15a");
        mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("pres_member15b"))))
                .andExpect(status().isCreated());
        login("pres_member15b", "password1", "d-15b");   // a ping only reaches a registered device

        // Ping just the first member
        mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", Long.parseLong(biz.get("memberId"))))))
                .andExpect(status().isCreated());

        // Immediately again → rejected, nothing recorded
        mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", Long.parseLong(biz.get("memberId"))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // A team-wide ping still reaches the OTHER member — one cooled-down member
        // doesn't block the rest
        mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pinged").value(1))
                .andExpect(jsonPath("$.data.presences[0].username").value("pres_member15b"));

        // Now both are cooling down → the team ping has nobody left to reach
        mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // 2 pings recorded in total, not 4
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void pagingIsStableAcrossEntriesSharingATimestamp() throws Exception {
        // A team-wide ping writes one row per member with the IDENTICAL clockDatetime, so
        // ordering by that column alone leaves the tie broken arbitrarily — page 1 and page 2
        // could then repeat or skip an entry. The id tiebreaker is what makes paging stable.
        Map<String, String> biz = businessWithMember("pres_admin16", "pres_member16a");
        for (String extra : List.of("pres_member16b", "pres_member16c")) {
            mockMvc.perform(post("/api/users/members")
                            .header("X-API-TOKEN", API_TOKEN)
                            .header("Authorization", "Bearer " + biz.get("adminToken"))
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(memberBody(extra))))
                    .andExpect(status().isCreated());
            login(extra, "password1", "d-" + extra);   // a ping only reaches a registered device
        }
        mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pinged").value(3));

        // Walk the pages: every entry must appear exactly once
        List<Integer> ids = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            MvcResult result = mockMvc.perform(get("/api/presences")
                            .header("X-API-TOKEN", API_TOKEN)
                            .header("Authorization", "Bearer " + biz.get("adminToken"))
                            .param("page", String.valueOf(page))
                            .param("size", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andReturn();
            objectMapper.readTree(result.getResponse().getContentAsString())
                    .at("/data").forEach(entry -> ids.add(entry.get("id").asInt()));
        }
        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void respondingNeedsALocationButTheNoteIsOptional() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin14", "pres_member14");
        MvcResult ping = mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(status().isCreated())
                .andReturn();
        String pingId = objectMapper.readTree(ping.getResponse().getContentAsString()).at("/data/presences/0/id").asText();

        // No body at all → rejected: a ping is a spot-check, the answer must say where they are
        mockMvc.perform(post("/api/presences/" + pingId + "/respond")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // A note but no coordinates → also rejected
        mockMvc.perform(post("/api/presences/" + pingId + "/respond")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("note", "di jalan"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // Location alone is enough — the note stays optional
        mockMvc.perform(post("/api/presences/" + pingId + "/respond")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.2088, "longitude", 106.8456))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.responseDatetime").isNotEmpty())
                .andExpect(jsonPath("$.data.latitude").value(-6.2088))
                .andExpect(jsonPath("$.data.note").doesNotExist());
    }

    @Test
    void pingGoesToTheWholeTeamOrToOneMember() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin13", "pres_member13a");
        // A second member in the same business
        mockMvc.perform(post("/api/users/members")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberBody("pres_member13b"))))
                .andExpect(status().isCreated());
        login("pres_member13b", "password1", "d-13b");   // a ping only reaches a registered device

        // userId → only that member gets an entry
        mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", Long.parseLong(biz.get("memberId"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pinged").value(1))
                .andExpect(jsonPath("$.data.totalMembers").value(2))
                .andExpect(jsonPath("$.data.presences[0].username").value("pres_member13a"));

        // An id outside the business is rejected — and nothing is recorded
        mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", 999999))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));

        // No userId → the whole team, but 13a was just pinged, so only 13b is reached
        // (the hour-long cooldown; see aMemberCannotBePingedTwiceWithinAnHour)
        mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pinged").value(1))          // only 13b: 13a is cooling down
                .andExpect(jsonPath("$.data.totalMembers").value(2))
                .andExpect(jsonPath("$.data.skipped[0].username").value("pres_member13a"))
                .andExpect(jsonPath("$.data.skipped[0].reason").value("COOLDOWN"))
                .andExpect(jsonPath("$.data.presences[0].username").value("pres_member13b"));

        // 2 pings total: one per member
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void memberCannotPingOrSeeAnotherMembersPresence() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin5", "pres_member5");

        mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(0));

        // Someone else's userId (the super admin's own entries) is off limits to a member
        long otherUserId = Long.parseLong(biz.get("memberId")) + 1000;
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .param("userId", String.valueOf(otherUserId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void pingWorksWithNoBodyAtAll() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin7", "pres_member7");

        // The body is optional — Spring passes null, which means "the whole team"
        mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.pinged").value(1))
                .andExpect(jsonPath("$.data.presences[0].type").value("PING"));
    }

    @Test
    void monthWithoutYearFallsBackToTheCurrentYear() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin8", "pres_member8");

        // An entry today — found by "this month" with no year given
        LocalDateTime now = LocalDateTime.now();
        clock(biz.get("memberToken"), "clock-in", now.toString());

        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .param("month", String.valueOf(now.getMonthValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        // …and an out-of-range month is still rejected
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void aMemberClocksInOnlyOncePerDay() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin10", "pres_member10");

        clock(biz.get("memberToken"), "clock-in", "2026-03-10T08:01:00");

        // A second clock-in the same day is rejected — and records nothing
        mockMvc.perform(post("/api/presences/clock-in")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.2, "longitude", 106.8,
                                "clockDatetime", "2026-03-10T09:30:00"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Kamu sudah absen masuk hari ini"));

        // A clock-out the SAME day is fine (one of each type), as is the next day's clock-in
        clock(biz.get("memberToken"), "clock-out", "2026-03-10T17:04:00");
        clock(biz.get("memberToken"), "clock-in", "2026-03-11T08:00:00");

        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken")))
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void presenceListIsOptionallyPaginated() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin12", "pres_member12");

        // 5 entries across 5 days (one clock-in per day — the daily cap)
        for (int day = 1; day <= 5; day++) {
            clock(biz.get("memberToken"), "clock-in", String.format("2026-05-%02dT08:00:00", day));
        }

        // No page/size → everything on page 1 (default size 50)
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(5))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.total").value(5));

        // size=2 → 3 pages; page 1 holds the two newest (newest first)
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.data[0].presenceDate").value("2026-05-05"))
                .andExpect(jsonPath("$.data[1].presenceDate").value("2026-05-04"));

        // Last page carries the remainder
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .param("page", "3").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.page").value(3))
                .andExpect(jsonPath("$.data[0].presenceDate").value("2026-05-01"));

        // Pagination composes with the filters (month + member)
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .param("userId", biz.get("memberId"))
                        .param("year", "2026").param("month", "5")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.total").value(5));
    }

    @Test
    void removedMembersEntriesRemainButAreFlaggedInactive() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin11", "pres_member11");
        clock(biz.get("memberToken"), "clock-in", "2026-03-12T08:00:00");

        // Still on the team → active
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(jsonPath("$.data[0].userActive").value(true));

        mockMvc.perform(delete("/api/users/members/" + biz.get("memberId"))
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(status().isOk());

        // History survives the removal, flagged so the app can mark them as ex-staff
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.data[0].username").value("pres_member11"))
                .andExpect(jsonPath("$.data[0].type").value("CLOCK_IN"))
                .andExpect(jsonPath("$.data[0].userActive").value(false));
    }

    @Test
    void respondingToAnotherBusinessesPingIs404() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin9", "pres_member9");
        MvcResult ping = mockMvc.perform(post("/api/presences/pings")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken"))
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andReturn();
        String pingId = objectMapper.readTree(ping.getResponse().getContentAsString()).at("/data/presences/0/id").asText();

        // An outsider can't even see the entry exists (a valid body, so this is the
        // ownership check answering, not validation)
        String outsiderToken = registerAdminWithTeam("pres_outsider9");
        mockMvc.perform(post("/api/presences/" + pingId + "/respond")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "latitude", -6.2088, "longitude", 106.8456))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(0));
    }

    @Test
    void presenceRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/presences").header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void presenceOfOneBusinessIsInvisibleToAnother() throws Exception {
        Map<String, String> biz = businessWithMember("pres_admin6", "pres_member6");
        clock(biz.get("memberToken"), "clock-in", "2026-03-11T08:00:00");

        // A second, unrelated business sees none of it
        String outsiderToken = registerAndLogin("pres_outsider6");
        mockMvc.perform(get("/api/presences")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    // ---------- ads history (merchant) ----------

    @Test
    void adHistoryIsEmptyForAFreshSuperAdmin() throws Exception {
        String token = registerAndLogin("ads_hist_admin1");

        mockMvc.perform(get("/api/ads/history")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void adHistoryIsRejectedForAPlainMember() throws Exception {
        Map<String, String> biz = businessWithMember("ads_hist_admin2", "ads_hist_member2");

        // Business-wide ad history is the super admin's — a member gets 403
        mockMvc.perform(get("/api/ads/history")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("memberToken")))
                .andExpect(status().isForbidden());

        // …and the super admin can read it
        mockMvc.perform(get("/api/ads/history")
                        .header("X-API-TOKEN", API_TOKEN)
                        .header("Authorization", "Bearer " + biz.get("adminToken")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(1));
    }

    // ---------- error handling ----------

    @Test
    void unknownRouteReturns404Json() throws Exception {
        mockMvc.perform(get("/api/does-not-exist")
                        .header("X-API-TOKEN", API_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(0));
    }
}
